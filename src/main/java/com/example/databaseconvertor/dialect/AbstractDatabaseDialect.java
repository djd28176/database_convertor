package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.ForeignKeyDefinition;
import com.example.databaseconvertor.model.IndexDefinition;
import com.example.databaseconvertor.model.TableDefinition;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public abstract class AbstractDatabaseDialect implements DatabaseDialect {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[\\t ]+");
    private static final Pattern MYSQL_ENGINE = Pattern.compile("\\)\\s*ENGINE\\s*=\\s*\\w+[^;\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_CHARSET = Pattern.compile("DEFAULT\\s+CHARSET\\s*=\\s*\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_COLLATE = Pattern.compile("COLLATE\\s*=\\s*\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_UPDATE_TIMESTAMP = Pattern.compile("ON\\s+UPDATE\\s+CURRENT_TIMESTAMP", Pattern.CASE_INSENSITIVE);
    private static final Pattern USING_BTREE = Pattern.compile("USING\\s+BTREE", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_IDENTIFIER = Pattern.compile("\\[(.+?)]");
    private static final Pattern BACKTICK_IDENTIFIER = Pattern.compile("`(.+?)`");

    @Override
    public String qualifyName(String schemaName, String tableName) {
        if (schemaName == null || schemaName.isBlank()) {
            return quoteIdentifier(tableName);
        }
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    @Override
    public String createTable(TableDefinition table, String targetSchema) {
        List<String> lines = new ArrayList<>();
        for (ColumnDefinition column : table.getColumns()) {
            lines.add("    " + toColumnDefinition(column));
        }
        if (!table.getPrimaryKeys().isEmpty()) {
            StringJoiner primaryKeyJoiner = new StringJoiner(", ");
            for (String primaryKey : table.getPrimaryKeys()) {
                primaryKeyJoiner.add(quoteIdentifier(primaryKey));
            }
            lines.add("    PRIMARY KEY (" + primaryKeyJoiner + ")");
        }
        return "CREATE TABLE " + qualifyName(resolveSchema(table, targetSchema), table.getName()) + " (\n"
                + String.join(",\n", lines)
                + "\n);";
    }

    @Override
    public List<String> createIndexes(TableDefinition table, String targetSchema) {
        List<String> statements = new ArrayList<>();
        for (IndexDefinition index : table.getIndexes()) {
            if (index.getColumns().isEmpty()) {
                continue;
            }
            StringJoiner joiner = new StringJoiner(", ");
            for (String column : index.getColumns()) {
                joiner.add(quoteIdentifier(column));
            }
            String unique = index.isUnique() ? "UNIQUE " : "";
            statements.add("CREATE " + unique + "INDEX " + quoteIdentifier(index.getName())
                    + " ON " + qualifyName(resolveSchema(table, targetSchema), table.getName())
                    + " (" + joiner + ");");
        }
        return statements;
    }

    @Override
    public List<String> createForeignKeys(TableDefinition table, String targetSchema) {
        List<String> statements = new ArrayList<>();
        for (ForeignKeyDefinition foreignKey : table.getForeignKeys()) {
            if (foreignKey.getColumnNames().isEmpty() || foreignKey.getReferencedColumnNames().isEmpty()) {
                continue;
            }
            StringJoiner sourceColumns = new StringJoiner(", ");
            for (String columnName : foreignKey.getColumnNames()) {
                sourceColumns.add(quoteIdentifier(columnName));
            }
            StringJoiner referencedColumns = new StringJoiner(", ");
            for (String columnName : foreignKey.getReferencedColumnNames()) {
                referencedColumns.add(quoteIdentifier(columnName));
            }
            statements.add("ALTER TABLE " + qualifyName(resolveSchema(table, targetSchema), table.getName())
                    + " ADD CONSTRAINT " + quoteIdentifier(foreignKey.getName())
                    + " FOREIGN KEY (" + sourceColumns + ") REFERENCES "
                    + qualifyName(resolveReferencedSchema(foreignKey, targetSchema), foreignKey.getReferencedTable())
                    + " (" + referencedColumns + ");");
        }
        return statements;
    }

    @Override
    public String createInsertStatements(TableDefinition table, List<Map<String, Object>> rows, String targetSchema) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringJoiner columnJoiner = new StringJoiner(", ");
        for (ColumnDefinition column : table.getColumns()) {
            columnJoiner.add(quoteIdentifier(column.getName()));
        }

        List<String> statements = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringJoiner valueJoiner = new StringJoiner(", ");
            for (ColumnDefinition column : table.getColumns()) {
                valueJoiner.add(literal(row.get(column.getName())));
            }
            statements.add("INSERT INTO " + qualifyName(resolveSchema(table, targetSchema), table.getName())
                    + " (" + columnJoiner + ") VALUES (" + valueJoiner + ");");
        }
        return String.join("\n", statements);
    }

    @Override
    public String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Clob clob) {
            return quoteStringValue(readClob(clob));
        }
        if (value instanceof NClob nClob) {
            return quoteStringValue(readClob(nClob));
        }
        if (value instanceof Blob blob) {
            return binaryLiteral(readBlob(blob));
        }
        if (value instanceof Number number) {
            if (number instanceof BigDecimal bigDecimal) {
                return bigDecimal.toPlainString();
            }
            return number.toString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Timestamp timestamp) {
            return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(timestamp) + "'";
        }
        if (value instanceof java.sql.Date date) {
            return "'" + date + "'";
        }
        if (value instanceof Time time) {
            return "'" + time + "'";
        }
        if (value instanceof Date date) {
            return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(date) + "'";
        }
        if (value instanceof LocalDate localDate) {
            return "'" + localDate + "'";
        }
        if (value instanceof LocalDateTime localDateTime) {
            return "'" + localDateTime.toString().replace('T', ' ') + "'";
        }
        if (value instanceof LocalTime localTime) {
            return "'" + localTime + "'";
        }
        if (value instanceof byte[] bytes) {
            return binaryLiteral(bytes);
        }
        return "'" + escapeString(String.valueOf(value)) + "'";
    }

    @Override
    public String transformSql(String sql) {
        String normalized = sql.replace("\r\n", "\n");
        normalized = BACKTICK_IDENTIFIER.matcher(normalized).replaceAll(matchResult ->
                quoteIdentifier(matchResult.group(1)));
        normalized = BRACKET_IDENTIFIER.matcher(normalized).replaceAll(matchResult ->
                quoteIdentifier(matchResult.group(1)));
        normalized = MYSQL_ENGINE.matcher(normalized).replaceAll(")");
        normalized = MYSQL_CHARSET.matcher(normalized).replaceAll("");
        normalized = MYSQL_COLLATE.matcher(normalized).replaceAll("");
        normalized = ON_UPDATE_TIMESTAMP.matcher(normalized).replaceAll("");
        normalized = USING_BTREE.matcher(normalized).replaceAll("");
        normalized = MULTIPLE_SPACES.matcher(normalized).replaceAll(" ");
        return applyTargetSpecificTransforms(normalized);
    }

    protected abstract String mapType(ColumnDefinition column);

    protected abstract String binaryLiteral(byte[] bytes);

    protected abstract String currentTimestampExpression();

    protected abstract String applyTargetSpecificTransforms(String sql);

    protected String autoIncrementClause(ColumnDefinition column) {
        return "";
    }

    protected String quoteStringValue(String value) {
        return "'" + escapeString(value) + "'";
    }

    protected String escapeString(String value) {
        return value.replace("'", "''");
    }

    protected String stripOuterParentheses(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null) {
            return null;
        }
        while (trimmed.startsWith("(") && trimmed.endsWith(")") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    protected String resolveSchema(TableDefinition table, String targetSchema) {
        return targetSchema == null || targetSchema.isBlank() ? table.getSchemaName() : targetSchema;
    }

    protected String resolveReferencedSchema(ForeignKeyDefinition foreignKey, String targetSchema) {
        return targetSchema == null || targetSchema.isBlank() ? foreignKey.getReferencedSchema() : targetSchema;
    }

    protected String replaceAllIgnoreCase(String value, String regex, String replacement) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).replaceAll(replacement);
    }

    @Override
    public String toColumnDefinition(ColumnDefinition column) {
        StringBuilder builder = new StringBuilder();
        builder.append(quoteIdentifier(column.getName())).append(' ').append(mapType(column));

        String autoIncrementClause = autoIncrementClause(column);
        if (!autoIncrementClause.isBlank()) {
            builder.append(' ').append(autoIncrementClause);
        }

        if (!column.isNullable()) {
            builder.append(" NOT NULL");
        }

        String defaultValue = normalizeDefaultValue(column.getDefaultValue());
        if (defaultValue != null && !defaultValue.isBlank() && !column.isAutoIncrement()) {
            builder.append(" DEFAULT ").append(defaultValue);
        }

        return builder.toString();
    }

    protected String normalizeDefaultValue(String rawDefaultValue) {
        if (rawDefaultValue == null || rawDefaultValue.isBlank()) {
            return null;
        }
        String cleaned = stripOuterParentheses(rawDefaultValue).trim();
        String upper = cleaned.toUpperCase(Locale.ROOT);
        if ("CURRENT_TIMESTAMP".equals(upper) || "NOW()".equals(upper) || "GETDATE()".equals(upper)
                || "SYSDATE".equals(upper) || "CURRENT DATE".equals(upper)) {
            return currentTimestampExpression();
        }
        if (upper.matches("-?\\d+(\\.\\d+)?")) {
            return cleaned;
        }
        if ((cleaned.startsWith("'") && cleaned.endsWith("'")) || (cleaned.startsWith("\"") && cleaned.endsWith("\""))) {
            return quoteStringValue(cleaned.substring(1, cleaned.length() - 1));
        }
        return quoteStringValue(cleaned);
    }

    protected String varcharType(int size, int defaultSize) {
        int resolved = size <= 0 ? defaultSize : size;
        return "VARCHAR(" + resolved + ")";
    }

    protected String numericType(ColumnDefinition column, String defaultType) {
        if (column.getSize() > 0 && column.getScale() >= 0) {
            return "DECIMAL(" + column.getSize() + ", " + column.getScale() + ")";
        }
        return defaultType;
    }

    protected String normalizeSourceType(ColumnDefinition column) {
        return column.getSourceTypeName() == null ? "" : column.getSourceTypeName().toUpperCase(Locale.ROOT);
    }

    protected String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    protected String detectStringType(ColumnDefinition column, String normalType, String largeType) {
        if (column.getSize() <= 0) {
            return normalType;
        }
        return column.getSize() > 4000 ? largeType : normalType;
    }

    protected String mapByJdbcType(ColumnDefinition column, String defaultVarcharType, String largeTextType,
                                   String datetimeType, String blobType, String booleanType) {
        JDBCType jdbcType;
        try {
            jdbcType = JDBCType.valueOf(column.getJdbcType());
        } catch (IllegalArgumentException ex) {
            jdbcType = JDBCType.OTHER;
        }
        return switch (jdbcType) {
            case BIT, BOOLEAN -> booleanType;
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INT";
            case BIGINT -> "BIGINT";
            case REAL -> "REAL";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL, NUMERIC -> numericType(column, "DECIMAL(18, 2)");
            case CHAR, NCHAR -> "CHAR(" + Math.max(column.getSize(), 1) + ")";
            case VARCHAR, NVARCHAR -> varcharType(column.getSize(), 255);
            case LONGVARCHAR, LONGNVARCHAR, CLOB, NCLOB -> largeTextType;
            case DATE -> "DATE";
            case TIME, TIME_WITH_TIMEZONE -> "TIME";
            case TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> datetimeType;
            case BINARY, VARBINARY, LONGVARBINARY, BLOB -> blobType;
            default -> {
                String sourceType = normalizeSourceType(column);
                if (sourceType.contains("TEXT") || sourceType.contains("CLOB")) {
                    yield largeTextType;
                }
                if (sourceType.contains("BLOB") || sourceType.contains("IMAGE")) {
                    yield blobType;
                }
                if (sourceType.contains("DATE") || sourceType.contains("TIME")) {
                    yield datetimeType;
                }
                yield defaultVarcharType;
            }
        };
    }

    protected String ensureText(String sql) {
        return new String(sql.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private String readClob(Clob clob) {
        try (Reader reader = clob.getCharacterStream()) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[2048];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, length);
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read CLOB value.", ex);
        }
    }

    private byte[] readBlob(Blob blob) {
        try (InputStream inputStream = blob.getBinaryStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toByteArray();
        } catch (IOException | java.sql.SQLException ex) {
            throw new IllegalStateException("Failed to read BLOB value.", ex);
        }
    }
}
