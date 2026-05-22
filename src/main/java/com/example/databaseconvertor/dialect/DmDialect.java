package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.IndexDefinition;
import com.example.databaseconvertor.model.TableDefinition;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DmDialect extends AbstractDatabaseDialect {

    private static final Pattern INLINE_CHARSET = Pattern.compile("(?i)\\s+(character\\s+set|charset)\\s*(=\\s*)?[A-Z0-9_]+");
    private static final Pattern INLINE_COLLATE = Pattern.compile("(?i)\\s+collate\\s*(=\\s*)?[A-Z0-9_]+");
    private static final Pattern INLINE_COMMENT = Pattern.compile("(?i)\\s+comment\\s+'((?:''|[^'])*)'");
    private static final Pattern TABLE_COMMENT = Pattern.compile("(?i)\\)\\s*comment\\s+'((?:''|[^'])*)'\\s*(?:charset\\s*=\\s*[A-Z0-9_]+)?\\s*$");
    private static final Pattern VARCHAR_LENGTH = Pattern.compile("(?i)\\bVARCHAR2?\\s*\\((\\d+)\\)");
    private static final Pattern CREATE_TABLE_PREFIX =
            Pattern.compile("(?is)^\\s*create\\s+table\\s+([\\w$.\"`\\[\\]]+)\\s*\\(");

    @Override
    public DatabaseType getType() {
        return DatabaseType.DM;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    protected String mapType(ColumnDefinition column) {
        String sourceType = normalizeSourceType(column);
        if (sourceType.contains("UNIQUEIDENTIFIER")) {
            return "VARCHAR2(36)";
        }
        String mapped = mapByJdbcType(column, "VARCHAR2(255)", "CLOB", "TIMESTAMP", "BLOB", "BIT");
        if (mapped.startsWith("VARCHAR") && column.getSize() > 4000) {
            return "CLOB";
        }
        if (mapped.startsWith("VARCHAR(")) {
            return mapped.replace("VARCHAR(", "VARCHAR2(");
        }
        if (mapped.equals("CHAR(" + Math.max(column.getSize(), 1) + ")")) {
            return mapped;
        }
        return mapped;
    }

    @Override
    protected String binaryLiteral(byte[] bytes) {
        return "HEXTORAW('" + hex(bytes) + "')";
    }

    @Override
    protected String currentTimestampExpression() {
        return "CURRENT_TIMESTAMP";
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
            statements.add("CREATE " + unique + "INDEX " + quoteIdentifier(resolveDmIndexName(table, index))
                    + " ON " + qualifyName(resolveSchema(table, targetSchema), table.getName())
                    + " (" + joiner + ");");
        }
        return statements;
    }

    @Override
    protected String applyTargetSpecificTransforms(String sql) {
        String transformed = ensureText(sql);
        transformed = replaceAllIgnoreCase(transformed, "\\bAUTO_INCREMENT\\b", "IDENTITY(1,1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bVARCHAR\\s*\\((\\d+)\\)", "VARCHAR2($1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bVARCHAR\\s*\\(MAX\\)", "CLOB");
        transformed = replaceAllIgnoreCase(transformed, "\\bNVARCHAR\\s*\\((MAX|\\d+)\\)", "VARCHAR2($1)");
        transformed = replaceAllIgnoreCase(transformed, "\\bDECIMAL\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)", "NUMBER($1, $2)");
        transformed = replaceAllIgnoreCase(transformed, "\\bDECIMAL\\b", "NUMBER");
        transformed = replaceAllIgnoreCase(transformed, "\\bDATETIME2\\b", "TIMESTAMP");
        transformed = replaceAllIgnoreCase(transformed, "\\bDATETIME\\b", "TIMESTAMP");
        transformed = replaceAllIgnoreCase(transformed, "\\bTEXT\\b", "CLOB");
        transformed = replaceAllIgnoreCase(transformed, "\\bVARBINARY\\s*\\(MAX\\)", "BLOB");
        transformed = replaceAllIgnoreCase(transformed, "\\bGETDATE\\(\\)", "CURRENT_TIMESTAMP");
        transformed = replaceAllIgnoreCase(transformed, "`([^`]+)`", "\"$1\"");
        transformed = replaceAllIgnoreCase(transformed, "\\[([^\\]]+)]", "\"$1\"");
        transformed = INLINE_CHARSET.matcher(transformed).replaceAll("");
        transformed = INLINE_COLLATE.matcher(transformed).replaceAll("");
        transformed = transformed.replaceAll("(?im)^\\s*GO\\s*$", "");
        transformed = convertLargeVarcharToClob(transformed);
        return rewriteCreateTableComments(transformed);
    }

    @Override
    public String transformSql(String sql, String targetSchema) {
        String transformed = transformSql(sql);
        if (targetSchema == null || targetSchema.isBlank()) {
            return transformed;
        }
        return applySchemaQualifier(transformed, targetSchema.trim());
    }

    private String convertLargeVarcharToClob(String sql) {
        Matcher matcher = VARCHAR_LENGTH.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int length = Integer.parseInt(matcher.group(1));
            String replacement = length > 4000 ? "CLOB" : "VARCHAR2(" + length + ")";
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String rewriteCreateTableComments(String sql) {
        List<String> outputs = new ArrayList<>();
        for (String statement : splitSqlStatements(sql)) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("create table")) {
                outputs.add(rewriteCreateTableStatement(trimmed));
            } else {
                outputs.add(trimmed.endsWith(";") ? trimmed : trimmed + ";");
            }
        }
        return String.join("\n\n", outputs).trim();
    }

    private List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                boolean escaped = i + 1 < sql.length() && sql.charAt(i + 1) == '\'';
                current.append(ch);
                if (escaped) {
                    current.append(sql.charAt(++i));
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }

        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private String rewriteCreateTableStatement(String statement) {
        Matcher prefixMatcher = CREATE_TABLE_PREFIX.matcher(statement);
        if (!prefixMatcher.find()) {
            return statement.endsWith(";") ? statement : statement + ";";
        }

        int bodyStart = prefixMatcher.end() - 1;
        int bodyEnd = findClosingParenthesis(statement, bodyStart);
        if (bodyEnd < 0) {
            return statement.endsWith(";") ? statement : statement + ";";
        }

        String tableName = prefixMatcher.group(1).trim();
        String body = statement.substring(bodyStart + 1, bodyEnd);
        String tail = statement.substring(bodyEnd);

        String tableComment = extractTableComment(tail);
        List<String> columnComments = new ArrayList<>();
        List<String> rewrittenLines = new ArrayList<>();

        for (String rawLine : body.split("\n")) {
            String line = cleanupLine(rawLine);
            String columnName = extractColumnName(line);
            String comment = extractInlineComment(line);
            if (comment != null && columnName != null) {
                columnComments.add("COMMENT ON COLUMN " + tableName + "." + columnName + " IS '" + comment + "';");
                line = INLINE_COMMENT.matcher(line).replaceAll("");
            }
            line = normalizeLineEnding(line);
            if (!line.isBlank()) {
                rewrittenLines.add(line);
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("CREATE TABLE ").append(tableName).append(" (\n");
        builder.append(String.join("\n", rewrittenLines));
        builder.append("\n);");

        if (tableComment != null) {
            builder.append("\n\nCOMMENT ON TABLE ").append(tableName).append(" IS '").append(tableComment).append("';");
        }
        if (!columnComments.isEmpty()) {
            builder.append("\n\n").append(String.join("\n", columnComments));
        }
        return builder.toString();
    }

    private int findClosingParenthesis(String statement, int openingIndex) {
        int depth = 1;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = openingIndex + 1; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                boolean escaped = i + 1 < statement.length() && statement.charAt(i + 1) == '\'';
                if (escaped) {
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (inSingleQuote || inDoubleQuote) {
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String cleanupLine(String line) {
        String cleaned = INLINE_CHARSET.matcher(line).replaceAll("");
        cleaned = INLINE_COLLATE.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace('\t', ' ');
        cleaned = cleaned.replaceAll(" {2,}", " ");
        return cleaned;
    }

    private String normalizeLineEnding(String line) {
        String normalized = line.replaceAll("\\s+,\\s*$", ",");
        normalized = normalized.replaceAll("\\s+$", "");
        normalized = normalized.replaceAll("(?i)\\bnull\\s+not\\s+null\\b", "NOT NULL");
        normalized = normalized.replaceAll("(?i)\\bdefault\\s+CURRENT_TIMESTAMP\\s+null\\b", "DEFAULT CURRENT_TIMESTAMP NULL");
        return normalized;
    }

    private String extractInlineComment(String line) {
        Matcher matcher = INLINE_COMMENT.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractTableComment(String tail) {
        Matcher matcher = TABLE_COMMENT.matcher(tail);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractColumnName(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CONSTRAINT ")
                || upper.startsWith("PRIMARY KEY")
                || upper.startsWith("UNIQUE ")
                || upper.startsWith("FOREIGN KEY")
                || upper.startsWith("KEY ")
                || upper.startsWith("INDEX ")
                || upper.startsWith("CHECK ")) {
            return null;
        }

        int index = 0;
        while (index < trimmed.length() && !Character.isWhitespace(trimmed.charAt(index))) {
            index++;
        }
        if (index == 0) {
            return null;
        }
        return trimmed.substring(0, index);
    }

    private String applySchemaQualifier(String sql, String targetSchema) {
        String transformed = sql;
        transformed = replaceTableReference(transformed, "(?i)\\b(CREATE\\s+TABLE\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(INSERT\\s+INTO\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(UPDATE\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(DELETE\\s+FROM\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(ALTER\\s+TABLE\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(COMMENT\\s+ON\\s+TABLE\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);
        transformed = replaceTableReference(transformed,
                "(?i)\\b(CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+.+?\\s+ON\\s+)([\"`\\[\\]\\w$.]+)",
                targetSchema);
        transformed = replaceTableReference(transformed, "(?i)\\b(REFERENCES\\s+)([\"`\\[\\]\\w$.]+)", targetSchema);

        Pattern columnCommentPattern = Pattern.compile("(?i)\\b(COMMENT\\s+ON\\s+COLUMN\\s+)([\"`\\[\\]\\w$.]+)\\.([\"`\\[\\]\\w$]+)");
        Matcher matcher = columnCommentPattern.matcher(transformed);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String tableRef = qualifyReference(matcher.group(2), targetSchema);
            String columnRef = stripQualifier(matcher.group(3));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + tableRef + "." + columnRef));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceTableReference(String sql, String regex, String targetSchema) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String qualified = qualifyReference(matcher.group(2), targetSchema);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + qualified));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String qualifyReference(String originalReference, String targetSchema) {
        String tableName = stripQualifier(originalReference);
        return targetSchema + "." + tableName;
    }

    private String stripQualifier(String reference) {
        int index = reference.lastIndexOf('.');
        if (index < 0) {
            return reference;
        }
        return reference.substring(index + 1);
    }

    private String resolveDmIndexName(TableDefinition table, IndexDefinition index) {
        String indexName = index.getName() == null || index.getName().isBlank() ? "IDX" : index.getName().trim();
        String tableName = table.getName() == null || table.getName().isBlank() ? "TABLE" : table.getName().trim();
        String normalizedIndexName = normalizeIdentifierPart(indexName);
        String normalizedTableName = normalizeIdentifierPart(tableName);

        String candidate;
        if (normalizedIndexName.toUpperCase(Locale.ROOT).endsWith("_" + normalizedTableName.toUpperCase(Locale.ROOT))) {
            candidate = normalizedIndexName;
        } else {
            candidate = normalizedIndexName + "_" + normalizedTableName;
        }

        if (candidate.length() <= 128) {
            return candidate;
        }
        int availableForTable = Math.min(normalizedTableName.length(), 40);
        int availableForIndex = Math.max(1, 127 - availableForTable);
        String shortenedIndex = normalizedIndexName.substring(0, Math.min(normalizedIndexName.length(), availableForIndex));
        String shortenedTable = normalizedTableName.substring(0, availableForTable);
        return shortenedIndex + "_" + shortenedTable;
    }

    private String normalizeIdentifierPart(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_]", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "IDX" : normalized;
    }
}
