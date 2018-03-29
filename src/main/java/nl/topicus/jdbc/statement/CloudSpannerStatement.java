package nl.topicus.jdbc.statement;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.ReadContext;
import com.google.rpc.Code;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.TokenMgrError;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import nl.topicus.jdbc.CloudSpannerConnection;
import nl.topicus.jdbc.exception.CloudSpannerSQLException;
import nl.topicus.jdbc.resultset.CloudSpannerPartitionResultSet;
import nl.topicus.jdbc.resultset.CloudSpannerResultSet;

/**
 * 
 * @author loite
 *
 */
public class CloudSpannerStatement extends AbstractCloudSpannerStatement
{
	protected List<ResultSet> currentResultSets = null;

	protected int currentResultSetIndex = 0;

	protected int lastUpdateCount = -1;

	private Pattern commentPattern = Pattern.compile("//.*|/\\*((.|\\n)(?!=*/))+\\*/|--.*(?=\\n)", Pattern.DOTALL);

	private BatchMode batchMode = BatchMode.NONE;

	private List<String> batchStatements = new ArrayList<>();

	enum BatchMode
	{
		NONE, DML, DDL;
	}

	public CloudSpannerStatement(CloudSpannerConnection connection, DatabaseClient dbClient)
	{
		super(connection, dbClient);
	}

	/**
	 * Does some formatting to DDL statements that might have been generated by
	 * standard SQL generators to make it compatible with Google Cloud Spanner.
	 * We also need to get rid of any comments, as Google Cloud Spanner does not
	 * accept comments in DDL-statements.
	 * 
	 * @param sql
	 *            The sql to format
	 * @return The formatted DDL statement.
	 */
	protected String formatDDLStatement(String sql)
	{
		String result = removeComments(sql);
		String[] parts = getTokens(sql, 0);
		if (parts.length > 2 && parts[0].equalsIgnoreCase("create") && parts[1].equalsIgnoreCase("table"))
		{
			String sqlWithSingleSpaces = String.join(" ", parts);
			int primaryKeyIndex = sqlWithSingleSpaces.toUpperCase().indexOf(", PRIMARY KEY (");
			if (primaryKeyIndex > -1)
			{
				int endPrimaryKeyIndex = sqlWithSingleSpaces.indexOf(')', primaryKeyIndex);
				String primaryKeySpec = sqlWithSingleSpaces.substring(primaryKeyIndex + 2, endPrimaryKeyIndex + 1);
				sqlWithSingleSpaces = sqlWithSingleSpaces.replace(", " + primaryKeySpec, "");
				sqlWithSingleSpaces = sqlWithSingleSpaces + " " + primaryKeySpec;
				result = sqlWithSingleSpaces.replaceAll("\\s+\\)", ")");
			}
		}

		return result;
	}

	/**
	 * Batching of DML and DDL statements together is not supported. The batch
	 * mode of a statement is determined by the first statement that is batched.
	 * All subsequent statements that are added to the batch must be of the same
	 * type.
	 * 
	 * @return The current batch mode of this statement
	 */
	public BatchMode getCurrentBatchMode()
	{
		return batchMode;
	}

	/**
	 * 
	 * @return An unmodifiable list of the currently batched statements that
	 *         will be executed if {@link #executeBatch()} is called.
	 */
	public List<String> getBatch()
	{
		return Collections.unmodifiableList(batchStatements);
	}

	@Override
	public void addBatch(String sql) throws SQLException
	{
		String[] sqlTokens = getTokens(sql);
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null)
		{
			throw new SQLFeatureNotSupportedException("Custom statements may not be batched");
		}
		if (isSelectStatement(sqlTokens))
		{
			throw new SQLFeatureNotSupportedException("SELECT statements may not be batched");
		}
		boolean ddlStatement = isDDLStatement(sqlTokens);
		if (batchMode == BatchMode.NONE)
		{
			if (ddlStatement)
			{
				batchMode = BatchMode.DDL;
			}
			else
			{
				batchMode = BatchMode.DML;
			}
		}
		if (batchMode == BatchMode.DDL)
		{
			if (!ddlStatement)
			{
				throw new SQLFeatureNotSupportedException(
						"DML statements may not be batched together with DDL statements");
			}
			batchStatements.add(formatDDLStatement(sql));
		}
		else
		{
			if (ddlStatement)
			{
				throw new SQLFeatureNotSupportedException(
						"DDL statements may not be batched together with DML statements");
			}
			batchStatements.add(sql);
		}
	}

	@Override
	public void clearBatch() throws SQLException
	{
		batchStatements.clear();
		batchMode = BatchMode.NONE;
	}

	@Override
	public int[] executeBatch() throws SQLException
	{
		int[] res = new int[batchStatements.size()];
		if (batchMode == BatchMode.DDL)
		{
			executeDDL(batchStatements);
		}
		else
		{
			int index = 0;
			for (String sql : batchStatements)
			{
				PreparedStatement ps = getConnection().prepareStatement(sql);
				res[index] = ps.executeUpdate();
				index++;
			}
		}
		batchStatements.clear();
		batchMode = BatchMode.NONE;
		return res;
	}

	protected int executeDDL(String ddl) throws SQLException
	{
		getConnection().executeDDL(Arrays.asList(ddl));
		return 0;
	}

	protected void executeDDL(List<String> ddl) throws SQLException
	{
		getConnection().executeDDL(ddl);
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException
	{
		if (getConnection().isBatchReadOnly())
		{
			throw new CloudSpannerSQLException(
					"The executeQuery()-method may not be called when in batch read-only mode",
					Code.FAILED_PRECONDITION);
		}

		String[] sqlTokens = getTokens(sql);
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null && custom.isQuery())
		{
			return custom.executeQuery(sqlTokens);
		}
		try (ReadContext context = getReadContext())
		{
			com.google.cloud.spanner.ResultSet rs = context.executeQuery(com.google.cloud.spanner.Statement.of(sql));
			return new CloudSpannerResultSet(this, rs);
		}
	}

	@Override
	public int executeUpdate(String sql) throws SQLException
	{
		String[] sqlTokens = getTokens(sql);
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null && !custom.isQuery())
		{
			return custom.executeUpdate(sqlTokens);
		}
		if (isDDLStatement(sqlTokens) && getConnection().isAutoBatchDdlOperations())
		{
			getConnection().addAutoBatchedDdlOperation(sql);
			return 0;
		}
		PreparedStatement ps = getConnection().prepareStatement(sql);
		return ps.executeUpdate();
	}

	@Override
	public boolean execute(String sql) throws SQLException
	{
		String[] sqlTokens = getTokens(sql);
		CustomDriverStatement custom = getCustomDriverStatement(sqlTokens);
		if (custom != null)
			return custom.execute(sqlTokens);
		Statement statement = null;
		boolean ddl = isDDLStatement(sqlTokens);
		if (!ddl)
		{
			try
			{
				statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
			}
			catch (JSQLParserException | TokenMgrError e)
			{
				throw new CloudSpannerSQLException(
						"Error while parsing sql statement " + sql + ": " + e.getLocalizedMessage(),
						Code.INVALID_ARGUMENT, e);
			}
		}
		if (!ddl && statement instanceof Select)
		{
			determineForceSingleUseReadContext((Select) statement);
			if (!isForceSingleUseReadContext() && getConnection().isBatchReadOnly())
			{
				List<Partition> partitions = partitionQuery(com.google.cloud.spanner.Statement.of(sql));
				currentResultSets = new ArrayList<>(partitions.size());
				for (Partition p : partitions)
				{
					currentResultSets.add(new CloudSpannerPartitionResultSet(this, getBatchReadOnlyTransaction(), p));
				}
			}
			else
			{
				try (ReadContext context = getReadContext())
				{
					com.google.cloud.spanner.ResultSet rs = context
							.executeQuery(com.google.cloud.spanner.Statement.of(sql));
					currentResultSets = Arrays.asList(new CloudSpannerResultSet(this, rs));
					currentResultSetIndex = 0;
					lastUpdateCount = -1;
				}
			}
			return true;
		}
		else
		{
			lastUpdateCount = executeUpdate(sql);
			currentResultSetIndex = 0;
			currentResultSets = null;
			return false;
		}
	}

	private static final String[] DDL_STATEMENTS = { "CREATE", "ALTER", "DROP" };

	/**
	 * Do a quick check if this SQL statement is a DDL statement
	 * 
	 * @param sqlTokens
	 *            The statement to check
	 * @return true if the SQL statement is a DDL statement
	 */
	protected boolean isDDLStatement(String[] sqlTokens)
	{
		if (sqlTokens.length > 0)
		{
			for (String statement : DDL_STATEMENTS)
			{
				if (sqlTokens[0].equalsIgnoreCase(statement))
					return true;
			}
		}

		return false;
	}

	/**
	 * Remove comments from the given sql string and split it into parts based
	 * on all space characters
	 * 
	 * @param sql
	 *            The sql string to split into tokens
	 * @return String array with all the parts of the sql statement
	 */
	protected String[] getTokens(String sql)
	{
		return getTokens(sql, 5);
	}

	/**
	 * Remove comments from the given sql string and split it into parts based
	 * on all space characters
	 * 
	 * @param sql
	 *            The sql statement to break into parts
	 * @param limit
	 *            The maximum number of times the pattern should be applied
	 * @return String array with all the parts of the sql statement
	 */
	protected String[] getTokens(String sql, int limit)
	{
		String result = removeComments(sql);
		String generated = result.replaceFirst("=", " = ");
		return generated.split("\\s+", limit);
	}

	protected String removeComments(String sql)
	{
		return commentPattern.matcher(sql).replaceAll("").trim();
	}

	protected boolean isSelectStatement(String[] sqlTokens)
	{
		return sqlTokens.length > 0 && sqlTokens[0].equalsIgnoreCase("SELECT");
	}

	public abstract class CustomDriverStatement
	{
		private final String statement;

		private final boolean query;

		private CustomDriverStatement(String statement, boolean query)
		{
			this.statement = statement;
			this.query = query;
		}

		protected final boolean isQuery()
		{
			return query;
		}

		protected final boolean execute(String[] sqlTokens) throws SQLException
		{
			if (query)
			{
				currentResultSets = Arrays.asList(executeQuery(sqlTokens));
				currentResultSetIndex = 0;
				lastUpdateCount = -1;
				return true;
			}
			else
			{
				currentResultSets = null;
				currentResultSetIndex = 0;
				lastUpdateCount = executeUpdate(sqlTokens);
				return false;
			}
		}

		protected ResultSet executeQuery(String[] sqlTokens) throws SQLException
		{
			throw new IllegalArgumentException("This statement is not valid for execution as a query");
		}

		protected int executeUpdate(String[] sqlTokens) throws SQLException
		{
			throw new IllegalArgumentException("This statement is not valid for execution as an update");
		}
	}

	private class ShowDdlOperations extends CustomDriverStatement
	{
		private ShowDdlOperations()
		{
			super("SHOW_DDL_OPERATIONS", true);
		}

		@Override
		public ResultSet executeQuery(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 1)
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for SHOW_DDL_OPERATIONS. Expected \"SHOW_DDL_OPERATIONS\"",
						Code.INVALID_ARGUMENT);
			return getConnection().getRunningDDLOperations(CloudSpannerStatement.this);
		}
	}

	private class CleanDdlOperations extends CustomDriverStatement
	{
		private CleanDdlOperations()
		{
			super("CLEAN_DDL_OPERATIONS", false);
		}

		@Override
		public int executeUpdate(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 1)
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for CLEAN_DDL_OPERATIONS. Expected \"CLEAN_DDL_OPERATIONS\"",
						Code.INVALID_ARGUMENT);
			return getConnection().clearFinishedDDLOperations();
		}
	}

	private class WaitForDdlOperations extends CustomDriverStatement
	{
		private WaitForDdlOperations()
		{
			super("WAIT_FOR_DDL_OPERATIONS", false);
		}

		@Override
		public int executeUpdate(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 1)
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for WAIT_FOR_DDL_OPERATIONS. Expected \"WAIT_FOR_DDL_OPERATIONS\"",
						Code.INVALID_ARGUMENT);
			getConnection().waitForDdlOperations();
			return 0;
		}
	}

	private class ExecuteDdlBatch extends CustomDriverStatement
	{
		private ExecuteDdlBatch()
		{
			super("EXECUTE_DDL_BATCH", false);
		}

		@Override
		public int executeUpdate(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 1)
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for EXECUTE_DDL_BATCH. Expected \"EXECUTE_DDL_BATCH\"",
						Code.INVALID_ARGUMENT);
			try (CloudSpannerStatement statement = getConnection().createStatement())
			{
				List<String> operations = getConnection().getAutoBatchedDdlOperations();
				for (String sql : operations)
					statement.addBatch(sql);
				statement.executeBatch();
				return operations.size();
			}
			finally
			{
				getConnection().clearAutoBatchedDdlOperations();
			}
		}
	}

	private class SetConnectionProperty extends CustomDriverStatement
	{
		private SetConnectionProperty()
		{
			super("SET_CONNECTION_PROPERTY", false);
		}

		@Override
		public int executeUpdate(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 4 || !"=".equals(sqlTokens[2]))
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for SET_CONNECTION_PROPERTY. Expected \"SET_CONNECTION_PROPERTY propertyName=propertyValue\"",
						Code.INVALID_ARGUMENT);
			return getConnection().setDynamicConnectionProperty(sqlTokens[1], sqlTokens[3]);
		}
	}

	private class GetConnectionProperty extends CustomDriverStatement
	{
		private GetConnectionProperty()
		{
			super("GET_CONNECTION_PROPERTY", true);
		}

		@Override
		public ResultSet executeQuery(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length == 1)
				return getConnection().getDynamicConnectionProperties(CloudSpannerStatement.this);
			if (sqlTokens.length == 2)
				return getConnection().getDynamicConnectionProperty(CloudSpannerStatement.this, sqlTokens[1]);
			throw new CloudSpannerSQLException(
					"Invalid argument(s) for GET_CONNECTION_PROPERTY. Expected \"GET_CONNECTION_PROPERTY propertyName\" or \"GET_CONNECTION_PROPERTY\"",
					Code.INVALID_ARGUMENT);
		}
	}

	private class ResetConnectionProperty extends CustomDriverStatement
	{
		private ResetConnectionProperty()
		{
			super("RESET_CONNECTION_PROPERTY", false);
		}

		@Override
		public int executeUpdate(String[] sqlTokens) throws SQLException
		{
			if (sqlTokens.length != 2)
				throw new CloudSpannerSQLException(
						"Invalid argument(s) for RESET_CONNECTION_PROPERTY. Expected \"RESET_CONNECTION_PROPERTY propertyName\"",
						Code.INVALID_ARGUMENT);
			return getConnection().resetDynamicConnectionProperty(sqlTokens[1]);
		}
	}

	private final List<CustomDriverStatement> customDriverStatements = Arrays.asList(new ShowDdlOperations(),
			new CleanDdlOperations(), new WaitForDdlOperations(), new ExecuteDdlBatch(), new SetConnectionProperty(),
			new GetConnectionProperty(), new ResetConnectionProperty());

	/**
	 * Checks if a sql statement is a custom statement only recognized by this
	 * driver
	 * 
	 * @param sqlTokens
	 *            The statement to check
	 * @return The custom driver statement if the given statement is a custom
	 *         statement only recognized by the Cloud Spanner JDBC driver, such
	 *         as show_ddl_operations
	 */
	protected CustomDriverStatement getCustomDriverStatement(String[] sqlTokens)
	{
		if (sqlTokens.length > 0)
		{
			for (CustomDriverStatement statement : customDriverStatements)
			{
				if (sqlTokens[0].equalsIgnoreCase(statement.statement))
				{
					return statement;
				}
			}
		}
		return null;
	}

	@Override
	public ResultSet getResultSet() throws SQLException
	{
		return currentResultSets == null || currentResultSetIndex >= currentResultSets.size() ? null
				: currentResultSets.get(currentResultSetIndex);
	}

	@Override
	public int getUpdateCount() throws SQLException
	{
		return lastUpdateCount;
	}

	@Override
	public boolean getMoreResults() throws SQLException
	{
		return moveToNextResult(CLOSE_CURRENT_RESULT);
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException
	{
		return moveToNextResult(current);
	}

	private boolean moveToNextResult(int current) throws SQLException
	{
		if (current != java.sql.Statement.KEEP_CURRENT_RESULT && currentResultSets != null
				&& currentResultSets.size() > currentResultSetIndex
				&& currentResultSets.get(currentResultSetIndex) != null)
		{
			currentResultSets.get(currentResultSetIndex).close();
		}
		if (currentResultSets != null && currentResultSets.size() > currentResultSetIndex
				&& currentResultSets.get(currentResultSetIndex) != null)
		{
			currentResultSets.set(currentResultSetIndex, null);
		}
		currentResultSetIndex++;
		lastUpdateCount = -1;

		return currentResultSets != null && currentResultSetIndex < currentResultSets.size();
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

}
