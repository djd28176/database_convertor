package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.TableDefinition;

import java.util.List;
import java.util.Map;

public class SqlServerDialect extends AbstractDatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.SQLSERVER;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier + "]";
    }

    @Override
    protected String mapType(ColumnDefinition column) {
        String sourceType = normalizeSourceType(column);
        if (sourceType.contains("UNIQUEIDENTIFIER")) {
            return "UNIQUEIDENTIFIER";
        }
        String mapped = mapByJdbcType(column, "VARCHAR(255)", "VARCHAR(MAX)", "DATETIME2", "VARBINARY(MAX)", "BIT");
        if ("TINYINT".equals(mapped) && column.getSize() == 1) {
            return "BIT";
        }
        if (mapped.startsWith("VARCHAR") && column.getSize() > 8000) {
            return "VARCHAR(MAX)";
        }
        return mapped;
    }

    @Override
    protected String binaryLiteral(byte[] bytes) {
        return "0x" + hex(bytes);
    }

    @Override
    protected String currentTimestampExpression() {
        return "GETDATE()";
    }

    @Override
    protected String autoIncrementClause(ColumnDefinition column) {
        return column.isAutoIncrement() ? "IDENTITY(1,1)" : "";
    }

    @Override
    public String createInsertStatements(TableDefinition table, List<Map<String, Object>> rows, String targetSchema) {
        String insertStatements = super.createInsertStatements(table, rows, targetSchema);
        if (insertStatements.isBlank()) {
            return insertStatements;
        }

        boolean hasIdentityColumn = table.getColumns().stream().anyMatch(ColumnDefinition::isAutoIncrement);
        if (!hasIdentityColumn) {
            return insertStatements;
        }

        String qualifiedTableName = qualifyName(resolveSchema(table, targetSchema), table.getName());
        return "SET IDENTITY_INSERT " + qualifiedTableName + " ON;\n"
                + insertStatements
                + "\nSET IDENTITY_INSERT " + qualifiedTableName + " OFF;";
    }

    @Override
    protected String applyTargetSpecificTransforms(String sql) {
        String transformed = ensureText(sql);
        transformed = replaceAllIgnoreCase(transformed, "\\bAUTO_INCREMENT\\b", "IDENTITY(1,1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bVARCHAR2\\s*\\(", "VARCHAR(");
        transformed = replaceAllIgnoreCase(transformed, "\\bNUMBER\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)", "DECIMAL($1, $2)");
        transformed = replaceAllIgnoreCase(transformed, "\\bNUMBER\\b", "DECIMAL");
        transformed = replaceAllIgnoreCase(transformed, "\\bTINYINT\\s*\\(\\s*1\\s*\\)", "BIT");
        transformed = replaceAllIgnoreCase(transformed, "\\bCLOB\\b", "VARCHAR(MAX)");
        transformed = replaceAllIgnoreCase(transformed, "\\bBLOB\\b", "VARBINARY(MAX)");
        transformed = replaceAllIgnoreCase(transformed, "\\bTEXT\\b", "VARCHAR(MAX)");
        transformed = replaceAllIgnoreCase(transformed, "\\bTIMESTAMP\\b", "DATETIME2");
        transformed = replaceAllIgnoreCase(transformed, "\\bCURRENT_TIMESTAMP\\b", "GETDATE()");
        transformed = replaceAllIgnoreCase(transformed, "\\bSYSDATE\\b", "GETDATE()");
        transformed = replaceAllIgnoreCase(transformed, "`([^`]+)`", "[$1]");
        transformed = replaceAllIgnoreCase(transformed, "\"([^\"]+)\"", "[$1]");
        return transformed;
    }
}
