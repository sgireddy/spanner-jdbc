package nl.topicus.jdbc.statement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Mutation.WriteBuilder;
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.ReadContext;
import com.google.rpc.Code;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.TokenMgrError;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.update.Update;
import nl.topicus.jdbc.CloudSpannerConnection;
import nl.topicus.jdbc.MetaDataStore.TableKeyMetaData;
import nl.topicus.jdbc.exception.CloudSpannerSQLException;
import nl.topicus.jdbc.resultset.CloudSpannerPartitionResultSet;
import nl.topicus.jdbc.resultset.CloudSpannerResultSet;
import nl.topicus.jdbc.statement.AbstractTablePartWorker.DMLOperation;

/**
 * 
 * @author loite
 *
 */
public class CloudSpannerPreparedStatement extends AbstractCloudSpannerPreparedStatement
{
	private static final String INVALID_WHERE_CLAUSE_DELETE_MESSAGE = "The DELETE statement does not contain a valid WHERE clause. DELETE statements must contain a WHERE clause specifying the value of the primary key of the record(s) to be deleted in the form 'ID=value' or 'ID1=value1 AND ID2=value2'";

	private static final String INVALID_WHERE_CLAUSE_UPDATE_MESSAGE = "The UPDATE statement does not contain a valid WHERE clause. UPDATE statements must contain a WHERE clause specifying the value of the primary key of the record(s) to be deleted in the form 'ID=value' or 'ID1=value1 AND ID2=value2'";

	private static final String METHOD_NOT_ON_PREPARED_STATEMENT = "This method may not be called on a PreparedStatement";

	static final String PARSE_ERROR = "Error while parsing sql statement ";

	private final String sql;

	private final String[] sqlTokens;

	/**
	 * Flag indicating that an INSERT INTO ... ON DUPLICATE KEY UPDATE statement
	 * should be forced to do only an update
	 */
	private boolean forceUpdate;

	private List<Mutations> batchMutations = new ArrayList<>();

	public CloudSpannerPreparedStatement(String sql, CloudSpannerConnection connection, DatabaseClient dbClient)
	{
		super(connection, dbClient);
		this.sql = sql;
		this.sqlTokens = getTokens(sql);
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException
	{
		throw new CloudSpannerSQLException(
				"The executeQuery(String sql)-method may not be called on a PreparedStatement",
				Code.FAILED_PRECONDITION);
	}

	@Override
	public int executeUpdate(String sql) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException
	{
		throw new CloudSpannerSQLException(METHOD_NOT_ON_PREPARED_STATEMENT, Code.FAILED_PRECONDITION);
	}

	@Override
	public ResultSet executeQuery() throws SQLException
	{
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null && custom.isQuery())
		{
			return custom.executeQuery(sqlTokens);
		}
		Statement statement;
		try
		{
			statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
		}
		catch (JSQLParserException | TokenMgrError e)
		{
			throw new CloudSpannerSQLException(PARSE_ERROR + sql + ": " + e.getLocalizedMessage(),
					Code.INVALID_ARGUMENT, e);
		}
		if (statement instanceof Select)
		{
			determineForceSingleUseReadContext((Select) statement);
			com.google.cloud.spanner.Statement.Builder builder = createSelectBuilder(statement, sql);
			try (ReadContext context = getReadContext())
			{
				com.google.cloud.spanner.ResultSet rs = context.executeQuery(builder.build());
				return new CloudSpannerResultSet(this, rs, sql);
			}
		}
		throw new CloudSpannerSQLException("SQL statement not suitable for executeQuery. Expected SELECT-statement.",
				Code.INVALID_ARGUMENT);
	}

	private com.google.cloud.spanner.Statement.Builder createSelectBuilder(Statement statement, String sql)
	{
		String namedSql = convertPositionalParametersToNamedParameters(sql);
		com.google.cloud.spanner.Statement.Builder builder = com.google.cloud.spanner.Statement.newBuilder(namedSql);
		setSelectParameters(((Select) statement).getSelectBody(), builder);

		return builder;
	}

	private String convertPositionalParametersToNamedParameters(String sql)
	{
		boolean inString = false;
		StringBuilder res = new StringBuilder(sql);
		int i = 0;
		int parIndex = 1;
		while (i < res.length())
		{
			char c = res.charAt(i);
			if (c == '\'')
			{
				inString = !inString;
			}
			else if (c == '?' && !inString)
			{
				res.replace(i, i + 1, "@p" + parIndex);
				parIndex++;
			}
			i++;
		}

		return res.toString();
	}

	private void setSelectParameters(SelectBody body, com.google.cloud.spanner.Statement.Builder builder)
	{
		if (body instanceof PlainSelect)
		{
			setPlainSelectParameters((PlainSelect) body, builder);
		}
		else
		{
			body.accept(new SelectVisitorAdapter()
			{
				@Override
				public void visit(PlainSelect plainSelect)
				{
					setPlainSelectParameters(plainSelect, builder);
				}
			});
		}
	}

	private void setPlainSelectParameters(PlainSelect plainSelect, com.google.cloud.spanner.Statement.Builder builder)
	{
		if (plainSelect.getFromItem() != null)
		{
			plainSelect.getFromItem().accept(new FromItemVisitorAdapter()
			{
				private int tableCount = 0;

				@Override
				public void visit(Table table)
				{
					tableCount++;
					if (tableCount == 1)
						getParameterStore().setTable(unquoteIdentifier(table.getFullyQualifiedName()));
					else
						getParameterStore().setTable(null);
				}

				@Override
				public void visit(SubSelect subSelect)
				{
					if (subSelect.getSelectBody() instanceof PlainSelect)
					{
						setPlainSelectParameters((PlainSelect) subSelect.getSelectBody(), builder);
					}
					else
					{
						subSelect.getSelectBody().accept(new SelectVisitorAdapter()
						{
							@Override
							public void visit(PlainSelect plainSelect)
							{
								setPlainSelectParameters(plainSelect, builder);
							}
						});
					}
				}

			});
		}
		setWhereParameters(plainSelect.getWhere(), builder);
		if (plainSelect.getLimit() != null)
		{
			setWhereParameters(plainSelect.getLimit().getRowCount(), builder);
		}
		if (plainSelect.getOffset() != null && plainSelect.getOffset().isOffsetJdbcParameter())
		{
			ValueBinderExpressionVisitorAdapter<com.google.cloud.spanner.Statement.Builder> binder = new ValueBinderExpressionVisitorAdapter<>(
					getParameterStore(), builder.bind("p" + getParameterStore().getHighestIndex()), null);
			binder.setValue(getParameterStore().getParameter(getParameterStore().getHighestIndex()), Types.BIGINT);
			getParameterStore().setType(getParameterStore().getHighestIndex(), Types.BIGINT);
		}
	}

	private void setWhereParameters(Expression where, com.google.cloud.spanner.Statement.Builder builder)
	{
		if (where != null)
		{
			where.accept(new ExpressionVisitorAdapter()
			{
				private String currentCol = null;

				@Override
				public void visit(Column col)
				{
					currentCol = unquoteIdentifier(col.getFullyQualifiedName());
				}

				@Override
				public void visit(JdbcParameter parameter)
				{
					parameter.accept(new ValueBinderExpressionVisitorAdapter<>(getParameterStore(),
							builder.bind("p" + parameter.getIndex()), currentCol));
					currentCol = null;
				}

				@Override
				public void visit(SubSelect subSelect)
				{
					setSelectParameters(subSelect.getSelectBody(), builder);
				}

			});
		}
	}

	private boolean isDDLStatement()
	{
		return isDDLStatement(sqlTokens);
	}

	@Override
	public void addBatch() throws SQLException
	{
		if (getConnection().getAutoCommit())
		{
			throw new SQLFeatureNotSupportedException(
					"Batching of statements is only allowed when not running in autocommit mode");
		}
		if (isDDLStatement())
		{
			throw new SQLFeatureNotSupportedException("DDL statements may not be batched");
		}
		if (isSelectStatement(sqlTokens))
		{
			throw new SQLFeatureNotSupportedException("SELECT statements may not be batched");
		}
		Mutations mutations = createMutations();
		batchMutations.add(mutations);
		getParameterStore().clearParameters();
	}

	@Override
	public void clearBatch() throws SQLException
	{
		batchMutations.clear();
		getParameterStore().clearParameters();
	}

	@Override
	public int[] executeBatch() throws SQLException
	{
		int[] res = new int[batchMutations.size()];
		int index = 0;
		for (Mutations mutation : batchMutations)
		{
			res[index] = (int) writeMutations(mutation);
			index++;
		}
		batchMutations.clear();
		getParameterStore().clearParameters();
		return res;
	}

	@Override
	public int executeUpdate() throws SQLException
	{
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null && !custom.isQuery())
		{
			return custom.executeUpdate(sqlTokens);
		}
		if (isDDLStatement())
		{
			String ddl = formatDDLStatement(sql);
			return executeDDL(ddl);
		}
		Mutations mutations = createMutations();
		return (int) writeMutations(mutations);
	}

	private Mutations createMutations() throws SQLException
	{
		return createMutations(sql, false, false);
	}

	private Mutations createMutations(String sql, boolean forceUpdate, boolean generateParameterMetaData)
			throws SQLException
	{
		try
		{
			if (getConnection().isReadOnly())
			{
				throw new CloudSpannerSQLException(NO_MUTATIONS_IN_READ_ONLY_MODE_EXCEPTION, Code.FAILED_PRECONDITION);
			}
			if (isDDLStatement())
			{
				throw new CloudSpannerSQLException(
						"Cannot create mutation for DDL statement. Expected INSERT, UPDATE or DELETE",
						Code.INVALID_ARGUMENT);
			}
			Statement statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
			if (statement instanceof Insert)
			{
				Insert insertStatement = (Insert) statement;
				if (generateParameterMetaData || insertStatement.getSelect() == null)
					return new Mutations(createInsertMutation(insertStatement, generateParameterMetaData));
				return new Mutations(createInsertWithSelectStatement(insertStatement, forceUpdate));
			}
			else if (statement instanceof Update)
			{
				Update updateStatement = (Update) statement;
				if (updateStatement.getSelect() != null)
					throw new CloudSpannerSQLException(
							"UPDATE statement using SELECT is not supported. Try to re-write the statement as an INSERT INTO ... SELECT A, B, C FROM TABLE WHERE ... ON DUPLICATE KEY UPDATE",
							Code.INVALID_ARGUMENT);
				if (updateStatement.getTables().size() > 1)
					throw new CloudSpannerSQLException(
							"UPDATE statement using multiple tables is not supported. Try to re-write the statement as an INSERT INTO ... SELECT A, B, C FROM TABLE WHERE ... ON DUPLICATE KEY UPDATE",
							Code.INVALID_ARGUMENT);

				if (generateParameterMetaData || isSingleRowWhereClause(
						getConnection().getTable(unquoteIdentifier(updateStatement.getTables().get(0).getName())),
						updateStatement.getWhere()))
					return new Mutations(createUpdateMutation(updateStatement, generateParameterMetaData));
				// Translate into an 'INSERT ... SELECT ... ON DUPLICATE KEY
				// UPDATE'-statement
				String insertSQL = createInsertSelectOnDuplicateKeyUpdateStatement(updateStatement);
				return createMutations(insertSQL, true, false);
			}
			else if (statement instanceof Delete)
			{
				Delete deleteStatement = (Delete) statement;
				if (generateParameterMetaData || deleteStatement.getWhere() == null
						|| isSingleRowWhereClause(
								getConnection().getTable(unquoteIdentifier(deleteStatement.getTable().getName())),
								deleteStatement.getWhere()))
					return new Mutations(createDeleteMutation(deleteStatement, generateParameterMetaData));
				return new Mutations(createDeleteWorker(deleteStatement));
			}
			else
			{
				throw new CloudSpannerSQLException(
						"Unrecognized or unsupported SQL-statment: Expected one of INSERT, UPDATE or DELETE. Please note that batching of prepared statements is not supported for SELECT-statements.",
						Code.INVALID_ARGUMENT);
			}
		}
		catch (JSQLParserException | IllegalArgumentException | TokenMgrError e)
		{
			throw new CloudSpannerSQLException(PARSE_ERROR + sql + ": " + e.getLocalizedMessage(),
					Code.INVALID_ARGUMENT, e);
		}
	}

	private Mutation createInsertMutation(Insert insert, boolean generateParameterMetaData) throws SQLException
	{
		ItemsList items = insert.getItemsList();
		if (generateParameterMetaData && items == null && insert.getSelect() != null)
		{
			// Just initialize the parameter meta data of the select statement
			createSelectBuilder(insert.getSelect(), insert.getSelect().toString());
			return null;
		}
		if (!(items instanceof ExpressionList))
		{
			throw new CloudSpannerSQLException("Insert statement must specify a list of values", Code.INVALID_ARGUMENT);
		}
		if (insert.getColumns() == null || insert.getColumns().isEmpty())
		{
			throw new CloudSpannerSQLException("Insert statement must specify a list of column names",
					Code.INVALID_ARGUMENT);
		}
		List<Expression> expressions = ((ExpressionList) items).getExpressions();
		String table = unquoteIdentifier(insert.getTable().getFullyQualifiedName());
		getParameterStore().setTable(table);
		WriteBuilder builder;
		if (insert.isUseDuplicate())
		{
			/**
			 * Do an insert-or-update. BUT: Cloud Spanner does not support
			 * supplying different values for the insert and update statements,
			 * meaning that only the values specified in the INSERT part of the
			 * statement will be considered. Anything specified in the 'ON
			 * DUPLICATE KEY UPDATE ...' statement will be ignored.
			 */
			if (this.forceUpdate)
				builder = Mutation.newUpdateBuilder(table);
			else
				builder = Mutation.newInsertOrUpdateBuilder(table);
		}
		else
		{
			/**
			 * Just do an insert and throw an error if a row with the specified
			 * key alread exists.
			 */
			builder = Mutation.newInsertBuilder(table);
		}
		int index = 0;
		for (Column col : insert.getColumns())
		{
			String columnName = unquoteIdentifier(col.getFullyQualifiedName());
			expressions.get(index).accept(new ValueBinderExpressionVisitorAdapter<>(getParameterStore(),
					builder.set(columnName), columnName));
			index++;
		}
		return builder.build();
	}

	private Mutation createUpdateMutation(Update update, boolean generateParameterMetaData) throws SQLException
	{
		if (update.getTables().isEmpty())
			throw new CloudSpannerSQLException("No table found in update statement", Code.INVALID_ARGUMENT);
		if (update.getTables().size() > 1)
			throw new CloudSpannerSQLException("Update statements for multiple tables at once are not supported",
					Code.INVALID_ARGUMENT);
		String table = unquoteIdentifier(update.getTables().get(0).getFullyQualifiedName());
		getParameterStore().setTable(table);
		List<Expression> expressions = update.getExpressions();
		WriteBuilder builder = Mutation.newUpdateBuilder(table);
		int index = 0;
		for (Column col : update.getColumns())
		{
			String columnName = unquoteIdentifier(col.getFullyQualifiedName());
			expressions.get(index).accept(new ValueBinderExpressionVisitorAdapter<>(getParameterStore(),
					builder.set(columnName), columnName));
			index++;
		}
		visitUpdateWhereClause(update.getWhere(), builder, generateParameterMetaData);

		return builder.build();
	}

	private Mutation createDeleteMutation(Delete delete, boolean generateParameterMetaData) throws SQLException
	{
		String table = unquoteIdentifier(delete.getTable().getFullyQualifiedName());
		getParameterStore().setTable(table);
		Expression where = delete.getWhere();
		if (where == null)
		{
			// Delete all
			return Mutation.delete(table, KeySet.all());
		}
		else
		{
			// Delete one
			DeleteKeyBuilder keyBuilder = new DeleteKeyBuilder(getConnection().getTable(table),
					generateParameterMetaData);
			visitDeleteWhereClause(where, keyBuilder, generateParameterMetaData);
			return Mutation.delete(table, keyBuilder.getKeyBuilder().build());
		}
	}

	private void visitDeleteWhereClause(Expression where, DeleteKeyBuilder keyBuilder,
			boolean generateParameterMetaData) throws SQLException
	{
		if (where != null)
		{
			DMLWhereClauseVisitor whereClauseVisitor = new DMLWhereClauseVisitor(getParameterStore())
			{

				@Override
				protected void visitExpression(Column col, Expression expression)
				{
					String columnName = unquoteIdentifier(col.getFullyQualifiedName());
					keyBuilder.set(columnName);
					expression.accept(
							new KeyBuilderExpressionVisitorAdapter(getParameterStore(), columnName, keyBuilder));
				}

			};
			where.accept(whereClauseVisitor);
			if (!generateParameterMetaData && !whereClauseVisitor.isValid())
			{
				throw new CloudSpannerSQLException(INVALID_WHERE_CLAUSE_DELETE_MESSAGE, Code.INVALID_ARGUMENT);
			}
		}
	}

	private boolean isSingleRowWhereClause(TableKeyMetaData table, Expression where)
	{
		if (where != null)
		{
			SingleRowWhereClauseValidator validator = new SingleRowWhereClauseValidator(table);
			DMLWhereClauseVisitor whereClauseVisitor = new DMLWhereClauseVisitor(getParameterStore())
			{

				@Override
				protected void visitExpression(Column col, Expression expression)
				{
					String columnName = unquoteIdentifier(col.getFullyQualifiedName());
					validator.set(columnName);
					expression.accept(
							new SingleRowWhereClauseValidatorExpressionVisitorAdapter(getParameterStore(), validator));
				}

			};
			where.accept(whereClauseVisitor);
			return whereClauseVisitor.isValid() && validator.isValid();
		}
		return false;
	}

	private void visitUpdateWhereClause(Expression where, WriteBuilder builder, boolean generateParameterMetaData)
			throws SQLException
	{
		if (where != null)
		{
			DMLWhereClauseVisitor whereClauseVisitor = new DMLWhereClauseVisitor(getParameterStore())
			{

				@Override
				protected void visitExpression(Column col, Expression expression)
				{
					String columnName = unquoteIdentifier(col.getFullyQualifiedName());
					expression.accept(new ValueBinderExpressionVisitorAdapter<>(getParameterStore(),
							builder.set(columnName), columnName));
				}

			};
			where.accept(whereClauseVisitor);
			if (!generateParameterMetaData && !whereClauseVisitor.isValid())
			{
				throw new CloudSpannerSQLException(INVALID_WHERE_CLAUSE_UPDATE_MESSAGE, Code.INVALID_ARGUMENT);
			}
		}
		else
		{
			throw new SQLException(INVALID_WHERE_CLAUSE_UPDATE_MESSAGE);
		}
	}

	@Override
	public boolean execute() throws SQLException
	{
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null)
			return custom.execute(sqlTokens);
		Statement statement = null;
		boolean ddl = isDDLStatement();
		if (!ddl)
		{
			try
			{
				statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
			}
			catch (JSQLParserException | TokenMgrError e)
			{
				throw new CloudSpannerSQLException(PARSE_ERROR + sql + ": " + e.getLocalizedMessage(),
						Code.INVALID_ARGUMENT, e);
			}
		}
		if (!ddl && statement instanceof Select)
		{
			determineForceSingleUseReadContext((Select) statement);
			com.google.cloud.spanner.Statement.Builder builder = createSelectBuilder(statement, sql);
			if (!isForceSingleUseReadContext() && getConnection().isBatchReadOnly())
			{
				List<Partition> partitions = partitionQuery(builder.build());
				currentResultSets = new ArrayList<>(partitions.size());
				for (Partition p : partitions)
				{
					currentResultSets
							.add(new CloudSpannerPartitionResultSet(this, getBatchReadOnlyTransaction(), p, sql));
				}
			}
			else
			{
				try (ReadContext context = getReadContext())
				{
					com.google.cloud.spanner.ResultSet rs = context.executeQuery(builder.build());
					currentResultSets = Arrays.asList(new CloudSpannerResultSet(this, rs, sql));
					currentResultSetIndex = 0;
					lastUpdateCount = -1;
				}
			}
			return true;
		}
		else
		{
			lastUpdateCount = executeUpdate();
			currentResultSets = null;
			currentResultSetIndex = 0;
			return false;
		}
	}

	@Override
	public CloudSpannerParameterMetaData getParameterMetaData() throws SQLException
	{
		// parse the SQL statement without executing it
		try
		{
			if (isDDLStatement())
			{
				throw new CloudSpannerSQLException("Cannot get parameter meta data for DDL statement",
						Code.INVALID_ARGUMENT);
			}
			Statement statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
			if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete)
			{
				// Create mutation, but don't do anything with it. This
				// initializes column names of the parameter store.
				createMutations(sql, false, true);
			}
			else if (statement instanceof Select)
			{
				// Create select builder, but don't do anything with it. This
				// initializes column names of the parameter store.
				createSelectBuilder(statement, sql);
			}
		}
		catch (JSQLParserException | TokenMgrError e)
		{
			throw new CloudSpannerSQLException(PARSE_ERROR + sql + ": " + e.getLocalizedMessage(),
					Code.INVALID_ARGUMENT, e);
		}
		return new CloudSpannerParameterMetaData(this);
	}

	private InsertWorker createInsertWithSelectStatement(Insert insert, boolean forceUpdate) throws SQLException
	{
		Select select = insert.getSelect();
		if (select == null)
		{
			throw new CloudSpannerSQLException("Insert statement must contain a select statement",
					Code.INVALID_ARGUMENT);
		}
		boolean isDuplicate = insert.isUseDuplicate();
		InsertWorker.DMLOperation mode;
		if (forceUpdate)
			mode = DMLOperation.UPDATE;
		else if (isDuplicate)
			mode = DMLOperation.ONDUPLICATEKEYUPDATE;
		else
			mode = DMLOperation.INSERT;
		return new InsertWorker(getConnection(), select, insert, getParameterStore(),
				getConnection().isAllowExtendedMode(), mode);
	}

	private DeleteWorker createDeleteWorker(Delete delete) throws SQLException
	{
		if (delete.getTable() == null || (delete.getTables() != null && !delete.getTables().isEmpty()))
		{
			throw new CloudSpannerSQLException("DELETE statement must contain only one table", Code.INVALID_ARGUMENT);
		}
		return new DeleteWorker(getConnection(), delete, getParameterStore(), getConnection().isAllowExtendedMode());
	}

	boolean isForceUpdate()
	{
		return forceUpdate;
	}

	void setForceUpdate(boolean forceUpdate)
	{
		this.forceUpdate = forceUpdate;
	}

}
