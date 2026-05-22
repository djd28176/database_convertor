package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;

import java.util.Locale;

public class MySqlDialect extends AbstractDatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public String qualifyName(String schemaName, String tableName) {
        return quoteIdentifier(tableName);
    }

    @Override
    protected String mapType(ColumnDefinition column) {
        String sourceType = normalizeSourceType(column);
        if (sourceType.contains("UNIQUEIDENTIFIER")) {
            return "CHAR(36)";
        }
        String mapped = mapByJdbcType(column, "VARCHAR(255)", "TEXT", "DATETIME", "LONGBLOB", "TINYINT(1)");
        if ("TINYINT".equals(mapped) && column.getSize() == 1) {
            return "TINYINT(1)";
        }
        if (mapped.startsWith("VARCHAR") && column.getSize() > 65535) {
            return "TEXT";
        }
        return mapped;
    }

    @Override
    protected String binaryLiteral(byte[] bytes) {
        return "X'" + hex(bytes) + "'";
    }

    @Override
    protected String currentTimestampExpression() {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    protected String autoIncrementClause(ColumnDefinition column) {
        return column.isAutoIncrement() ? "AUTO_INCREMENT" : "";
    }

    @Override
    protected String applyTargetSpecificTransforms(String sql) {
        String transformed = ensureText(sql);
        transformed = replaceAllIgnoreCase(transformed, "\\bNVARCHAR\\s*\\((MAX|\\d+)\\)", "VARCHAR($1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bVARCHAR2\\s*\\(", "VARCHAR(");
        transformed = replaceAllIgnoreCase(transformed, "\\bNUMBER\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)", "DECIMAL($1, $2)");
        transformed = replaceAllIgnoreCase(transformed, "\\bNUMBER\\b", "DECIMAL");
        transformed = replaceAllIgnoreCase(transformed, "\\bDATETIME2\\b", "DATETIME");
        transformed = replaceAllIgnoreCase(transformed, "\\bTIMESTAMP\\b", "DATETIME");
        transformed = replaceAllIgnoreCase(transformed, "\\bCLOB\\b", "TEXT");
        transformed = replaceAllIgnoreCase(transformed, "\\bBLOB\\b", "LONGBLOB");
        transformed = replaceAllIgnoreCase(transformed, "\\bBIT\\b", "TINYINT(1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bIDENTITY\\s*\\(\\s*1\\s*,\\s*1\\s*\\)", "AUTO_INCREMENT");
        transformed = replaceAllIgnoreCase(transformed, "\\bGETDATE\\(\\)", "CURRENT_TIMESTAMP");
        transformed = replaceAllIgnoreCase(transformed, "\\bSYSDATE\\b", "CURRENT_TIMESTAMP");
        transformed = replaceAllIgnoreCase(transformed, "\"([^\"]+)\"", "`$1`");
        return transformed.replaceAll("(?im)^\\s*GO\\s*$", "");
    }
}
