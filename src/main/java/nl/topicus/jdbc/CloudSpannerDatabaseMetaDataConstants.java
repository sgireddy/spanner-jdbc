package nl.topicus.jdbc;

import java.sql.Types;

public abstract class CloudSpannerDatabaseMetaDataConstants
{
	public static final String WHERE_1_EQUALS_1 = " WHERE 1=1 ";

	public static final String FROM_TABLES_T = " FROM INFORMATION_SCHEMA.TABLES AS T ";

	private static final String UNION_ALL = "UNION ALL\n";

	private static final String CASE = "CASE \n";

	public static final String GET_TYPE_INFO = "SELECT 'BOOL' AS TYPE_NAME, 16 AS DATA_TYPE, 1 AS PRECISION, NULL AS LITERAL_PREFIX, NULL AS LITERAL_SUFFIX, NULL AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, TRUE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'BOOL' AS LOCAL_TYPE_NAME, 1 AS MINIMUM_SCALE, 1 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'BYTES' AS TYPE_NAME, -2 AS DATA_TYPE, 5000000 AS PRECISION, '0x' AS LITERAL_PREFIX, NULL AS LITERAL_SUFFIX, 'LENGTH' AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, TRUE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'BYTES' AS LOCAL_TYPE_NAME, 1 AS MINIMUM_SCALE, 5000000 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'DATE' AS TYPE_NAME, 91 AS DATA_TYPE, 8 AS PRECISION, \"{d '\" AS LITERAL_PREFIX, \"'}\" AS LITERAL_SUFFIX, NULL AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, TRUE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'DATE' AS LOCAL_TYPE_NAME, 8 AS MINIMUM_SCALE, 8 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'FLOAT64' AS TYPE_NAME, 8 AS DATA_TYPE, 15 AS PRECISION, NULL AS LITERAL_PREFIX, NULL AS LITERAL_SUFFIX, NULL AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, FALSE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'FLOAT64' AS LOCAL_TYPE_NAME, 15 AS MINIMUM_SCALE, 15 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'INT64' AS TYPE_NAME, -5 AS DATA_TYPE, 19 AS PRECISION, NULL AS LITERAL_PREFIX, NULL AS LITERAL_SUFFIX, NULL AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, FALSE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'INT64' AS LOCAL_TYPE_NAME, 19 AS MINIMUM_SCALE, 19 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'STRING' AS TYPE_NAME, -9 AS DATA_TYPE, 5000000 AS PRECISION, \"'\" AS LITERAL_PREFIX, \"'\" AS LITERAL_SUFFIX, 'LENGTH' AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, TRUE AS CASE_SENSITIVE, 3 AS SEARCHABLE, TRUE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'STRING' AS LOCAL_TYPE_NAME, 1 AS MINIMUM_SCALE, 5000000 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX\n"

			+ UNION_ALL

			+ "SELECT 'TIMESTAMP' AS TYPE_NAME, 93 AS DATA_TYPE, 19 AS PRECISION, \"{ts '\" AS LITERAL_PREFIX, \"'}\" AS LITERAL_SUFFIX, NULL AS CREATE_PARAMS, 1 AS TYPE_NULLABLE, FALSE AS CASE_SENSITIVE, 2 AS SEARCHABLE, TRUE AS UNSIGNED_ATTRIBUTE, FALSE AS FIXED_PREC_SCALE, FALSE AS AUTO_INCREMENT, 'TIMESTAMP' AS LOCAL_TYPE_NAME, 19 AS MINIMUM_SCALE, 19 AS MAXIMUM_SCALE, NULL AS SQL_DATA_TYPE, NULL AS SQL_DATETIME_SUB, NULL AS NUM_PREC_RADIX";

	public static final String GET_UDTS = "SELECT *\n"
			+ "FROM (SELECT NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, NULL AS CLASS_NAME, NULL AS DATA_TYPE, NULL AS REMARKS, NULL AS BASE_TYPE) UDTS\n"
			+ "WHERE 1=2";

	public static final String GET_COLUMNS = "SELECT TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, \n"

			+ CASE

			+ "	WHEN SPANNER_TYPE LIKE 'ARRAY%' THEN " + Types.ARRAY + " \n"

			+ "	WHEN SPANNER_TYPE = 'BOOL' THEN " + Types.BOOLEAN + " \n"

			+ "	WHEN SPANNER_TYPE LIKE 'BYTES%' THEN " + Types.BINARY + " \n"

			+ "	WHEN SPANNER_TYPE = 'DATE' THEN " + Types.DATE + " \n"

			+ "	WHEN SPANNER_TYPE = 'FLOAT64' THEN " + Types.DOUBLE + " \n"

			+ "	WHEN SPANNER_TYPE = 'INT64' THEN " + Types.BIGINT + " \n"

			+ "	WHEN SPANNER_TYPE LIKE 'STRING%' THEN " + Types.NVARCHAR + " \n"

			+ "	WHEN SPANNER_TYPE LIKE 'STRUCT%' THEN " + Types.STRUCT + " \n"

			+ "	WHEN SPANNER_TYPE = 'TIMESTAMP' THEN " + Types.TIMESTAMP + " \n"

			+ "END AS DATA_TYPE, \n"

			+ "SPANNER_TYPE AS TYPE_NAME, \n"

			+ CASE

			+ "WHEN strpos(spanner_type, '(')=0 then 0 \n"

			+ "ELSE cast(replace(substr(spanner_type, strpos(spanner_type, '(')+1, strpos(spanner_type, ')')-strpos(spanner_type, '(')-1), 'MAX', '0') as INT64) \n"

			+ "END AS COLUMN_SIZE, \n"

			+ "0 AS BUFFER_LENGTH, NULL AS DECIMAL_DIGITS, 0 AS NUM_PREC_RADIX, \n"

			+ CASE

			+ "	WHEN IS_NULLABLE = 'YES' THEN 1 \n"

			+ "	WHEN IS_NULLABLE = 'NO' THEN 0 \n"

			+ "	ELSE 2 \n"

			+ "END AS NULLABLE, NULL AS REMARKS, NULL AS COLUMN_DEF, 0 AS SQL_DATA_TYPE, 0 AS SQL_DATETIME_SUB, 0 AS CHAR_OCTET_LENGTH, ORDINAL_POSITION, IS_NULLABLE, NULL AS SCOPE_CATALOG, \n"

			+ "NULL AS SCOPE_SCHEMA, NULL AS SCOPE_TABLE, NULL AS SOURCE_DATA_TYPE, 'NO' AS IS_AUTOINCREMENT, 'NO' AS IS_GENERATEDCOLUMN \n"

			+ "FROM INFORMATION_SCHEMA.COLUMNS " + WHERE_1_EQUALS_1;

}
