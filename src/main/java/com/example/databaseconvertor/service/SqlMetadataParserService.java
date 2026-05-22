package com.example.databaseconvertor.service;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.TableDefinition;
import com.example.databaseconvertor.util.SqlStatementUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlMetadataParserService {

    private static final Logger log = LoggerFactory.getLogger(SqlMetadataParserService.class);

    private static final Pattern COMMENT_ON_TABLE_PATTERN = Pattern.compile(
            "(?is)^COMMENT\\s+ON\\s+TABLE\\s+(.+?)\\s+IS\\s+'((?:''|[^'])*)'$");
    private static final Pattern COMMENT_ON_COLUMN_PATTERN = Pattern.compile(
            "(?is)^COMMENT\\s+ON\\s+COLUMN\\s+(.+?)\\s+IS\\s+'((?:''|[^'])*)'$");
    private static final Pattern MYSQL_TABLE_COMMENT_PATTERN = Pattern.compile(
            "(?is)\\)\\s*(?:ENGINE\\s*=\\s*\\w+\\s*)?(?:DEFAULT\\s+)?(?:CHARSET\\s*=\\s*\\w+\\s*)?(?:COLLATE\\s*=\\s*\\w+\\s*)?(?:COMMENT\\s*[= ]\\s*'((?:''|[^'])*)')?");
    private static final Pattern INLINE_COMMENT_PATTERN = Pattern.compile(
            "(?is)\\bCOMMENT\\s+'((?:''|[^'])*)'");
    private static final Pattern TYPE_END_PATTERN = Pattern.compile(
            "(?i)\\s+(NOT\\s+NULL|NULL\\b|DEFAULT\\b|COMMENT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b|REFERENCES\\b|COLLATE\\b|CHARSET\\b|CONSTRAINT\\b)");

    public List<TableDefinition> parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("建表 SQL 不能为空。");
        }

        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        List<String> statements = SqlStatementUtils.splitStatements(sql);
        log.info("开始解析建表 SQL: statementCount={}", statements.size());

        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length())) {
                TableDefinition table = parseCreateTable(trimmed);
                tables.put(normalizeTableKey(table.getSchemaName(), table.getName()), table);
            }
        }

        for (String statement : statements) {
            String trimmed = statement.trim();
            applyCommentStatement(tables, trimmed);
        }

        log.info("建表 SQL 解析完成: tableCount={}", tables.size());
        return new ArrayList<>(tables.values());
    }

    private TableDefinition parseCreateTable(String statement) {
        int firstParen = statement.indexOf('(');
        if (firstParen < 0) {
            throw new IllegalArgumentException("无法识别 CREATE TABLE 语句: " + abbreviate(statement));
        }

        int matchingParen = findMatchingParenthesis(statement, firstParen);
        if (matchingParen < 0) {
            throw new IllegalArgumentException("CREATE TABLE 括号不完整: " + abbreviate(statement));
        }

        String header = statement.substring(0, firstParen).trim();
        String body = statement.substring(firstParen + 1, matchingParen);
        String tail = statement.substring(matchingParen);

        String tableReference = header.replaceFirst("(?is)^CREATE\\s+TABLE\\s+", "").trim();
        String[] tableParts = splitIdentifierParts(tableReference);

        TableDefinition table = new TableDefinition();
        if (tableParts.length >= 2) {
            table.setSchemaName(tableParts[tableParts.length - 2]);
        }
        table.setName(tableParts[tableParts.length - 1]);
        table.setRemarks(extractMysqlTableComment(tail));

        for (String segment : splitTopLevel(body, ',')) {
            String line = segment.trim();
            if (line.isBlank() || isConstraintLine(line)) {
                continue;
            }
            table.getColumns().add(parseColumn(line));
        }

        return table;
    }

    private void applyCommentStatement(Map<String, TableDefinition> tables, String statement) {
        Matcher tableMatcher = COMMENT_ON_TABLE_PATTERN.matcher(statement);
        if (tableMatcher.matches()) {
            String[] tableParts = splitIdentifierParts(tableMatcher.group(1).trim());
            TableDefinition table = tables.get(normalizeTableKey(
                    tableParts.length >= 2 ? tableParts[tableParts.length - 2] : null,
                    tableParts[tableParts.length - 1]));
            if (table != null) {
                table.setRemarks(unescapeSqlString(tableMatcher.group(2)));
            }
            return;
        }

        Matcher columnMatcher = COMMENT_ON_COLUMN_PATTERN.matcher(statement);
        if (columnMatcher.matches()) {
            String[] parts = splitIdentifierParts(columnMatcher.group(1).trim());
            if (parts.length == 0) {
                return;
            }
            String columnName = parts[parts.length - 1];
            String tableName = parts.length >= 2 ? parts[parts.length - 2] : null;
            String schemaName = parts.length >= 3 ? parts[parts.length - 3] : null;
            TableDefinition table = tables.get(normalizeTableKey(schemaName, tableName));
            if (table == null) {
                return;
            }
            for (ColumnDefinition column : table.getColumns()) {
                if (column.getName().equalsIgnoreCase(columnName)) {
                    column.setRemarks(unescapeSqlString(columnMatcher.group(2)));
                    break;
                }
            }
        }
    }

    private ColumnDefinition parseColumn(String line) {
        int identifierLength = consumeIdentifier(line);
        if (identifierLength <= 0) {
            throw new IllegalArgumentException("无法识别字段定义: " + line);
        }

        String rawName = line.substring(0, identifierLength).trim();
        String remainder = line.substring(identifierLength).trim();
        ColumnDefinition column = new ColumnDefinition();
        column.setName(cleanIdentifier(rawName));
        column.setRemarks(extractInlineComment(remainder));
        column.setSourceTypeName(extractTypeName(remainder));
        applyLengthMetadata(column);
        column.setNullable(!remainder.toUpperCase(Locale.ROOT).contains("NOT NULL"));
        column.setAutoIncrement(containsIgnoreCase(remainder, "AUTO_INCREMENT")
                || containsIgnoreCase(remainder, "IDENTITY"));
        column.setJdbcType(inferJdbcType(column.getSourceTypeName()));
        return column;
    }

    private void applyLengthMetadata(ColumnDefinition column) {
        String type = column.getSourceTypeName();
        if (type == null) {
            return;
        }
        Matcher matcher = Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(type);
        if (!matcher.find()) {
            return;
        }
        column.setSize(Integer.parseInt(matcher.group(1)));
        column.setScale(matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)));
    }

    private int inferJdbcType(String typeName) {
        if (typeName == null) {
            return Types.VARCHAR;
        }
        String normalized = typeName.toUpperCase(Locale.ROOT);
        if (normalized.contains("BIGINT")) {
            return Types.BIGINT;
        }
        if (normalized.contains("INT")) {
            return Types.INTEGER;
        }
        if (normalized.contains("DECIMAL") || normalized.contains("NUMERIC") || normalized.contains("NUMBER")) {
            return Types.DECIMAL;
        }
        if (normalized.contains("DATE") && !normalized.contains("TIME")) {
            return Types.DATE;
        }
        if (normalized.contains("TIME") || normalized.contains("DATETIME") || normalized.contains("TIMESTAMP")) {
            return Types.TIMESTAMP;
        }
        if (normalized.contains("TEXT") || normalized.contains("CLOB")) {
            return Types.CLOB;
        }
        return Types.VARCHAR;
    }

    private String extractInlineComment(String remainder) {
        Matcher matcher = INLINE_COMMENT_PATTERN.matcher(remainder);
        if (matcher.find()) {
            return unescapeSqlString(matcher.group(1));
        }
        return null;
    }

    private String extractMysqlTableComment(String tail) {
        Matcher matcher = MYSQL_TABLE_COMMENT_PATTERN.matcher(tail);
        if (matcher.find() && matcher.group(1) != null) {
            return unescapeSqlString(matcher.group(1));
        }
        return null;
    }

    private String extractTypeName(String remainder) {
        Matcher matcher = TYPE_END_PATTERN.matcher(remainder);
        String type = matcher.find() ? remainder.substring(0, matcher.start()) : remainder;
        return type.replaceAll("[,;]+$", "").trim();
    }

    private boolean isConstraintLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.startsWith("PRIMARY KEY")
                || upper.startsWith("UNIQUE")
                || upper.startsWith("KEY ")
                || upper.startsWith("INDEX ")
                || upper.startsWith("CONSTRAINT ")
                || upper.startsWith("FOREIGN KEY");
    }

    private int findMatchingParenthesis(String value, int openIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = openIndex; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && !inDoubleQuote && !isEscaped(value, i)) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote && !isEscaped(value, i)) {
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

    private List<String> splitTopLevel(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDoubleQuote && !isEscaped(text, i)) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote && !isEscaped(text, i)) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == delimiter && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private int consumeIdentifier(String line) {
        if (line.isBlank()) {
            return -1;
        }
        char first = line.charAt(0);
        if (first == '`' || first == '"' || first == '[') {
            char end = first == '[' ? ']' : first;
            int endIndex = line.indexOf(end, 1);
            return endIndex < 0 ? -1 : endIndex + 1;
        }
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (Character.isWhitespace(ch)) {
                break;
            }
            index++;
        }
        return index;
    }

    private String[] splitIdentifierParts(String identifier) {
        String normalized = identifier.trim()
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "");
        return normalized.split("\\.");
    }

    private String cleanIdentifier(String identifier) {
        return identifier.trim()
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "");
    }

    private String normalizeTableKey(String schemaName, String tableName) {
        String schemaPart = schemaName == null || schemaName.isBlank() ? "" : schemaName.trim().toLowerCase(Locale.ROOT) + ".";
        return schemaPart + tableName.trim().toLowerCase(Locale.ROOT);
    }

    private String unescapeSqlString(String value) {
        return value == null ? null : value.replace("''", "'");
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source.toUpperCase(Locale.ROOT).contains(keyword.toUpperCase(Locale.ROOT));
    }

    private boolean isEscaped(String text, int index) {
        return index > 0 && text.charAt(index - 1) == '\\';
    }

    private String abbreviate(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 180) + " ..." : normalized;
    }
}
