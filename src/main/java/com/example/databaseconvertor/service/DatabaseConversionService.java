package com.example.databaseconvertor.service;

import com.example.databaseconvertor.dialect.DatabaseDialect;
import com.example.databaseconvertor.dialect.DatabaseDialectFactory;
import com.example.databaseconvertor.dialect.DatabaseType;
import com.example.databaseconvertor.dto.ConversionResultResponse;
import com.example.databaseconvertor.dto.DatabaseConversionRequest;
import com.example.databaseconvertor.model.TableDefinition;
import com.example.databaseconvertor.util.SqlStatementUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DatabaseConversionService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConversionService.class);

    private static final Pattern IDENTITY_AWARE_INSERT_PATTERN =
            Pattern.compile("(?is)^INSERT\\s+INTO\\s+([^\\s(]+)\\s*\\(([^)]*)\\)\\s*VALUES\\b");
    private static final Pattern IDENTITY_INSERT_PATTERN =
            Pattern.compile("(?is)^SET\\s+IDENTITY_INSERT\\s+([^\\s]+)\\s+(ON|OFF)\\s*$");

    private final MetadataExtractionService metadataExtractionService;
    private final DatabaseDialectFactory dialectFactory;
    private final JdbcConnectionService jdbcConnectionService;

    public DatabaseConversionService(MetadataExtractionService metadataExtractionService,
                                     DatabaseDialectFactory dialectFactory,
                                     JdbcConnectionService jdbcConnectionService) {
        this.metadataExtractionService = metadataExtractionService;
        this.dialectFactory = dialectFactory;
        this.jdbcConnectionService = jdbcConnectionService;
    }

    public ConversionResultResponse convert(DatabaseConversionRequest request) throws SQLException {
        validate(request);

        DatabaseDialect sourceDialect = dialectFactory.getDialect(request.source().type());
        DatabaseDialect targetDialect = dialectFactory.getDialect(request.target().type());
        boolean includeData = Boolean.TRUE.equals(request.includeData());
        String targetSchema = resolveTargetSchema(request);
        log.info("开始数据库转换: sourceType={}, targetType={}, includeData={}, executeOnTarget={}, targetSchema={}",
                request.source().type(), request.target().type(), includeData, request.executeOnTarget(), targetSchema);

        List<TableDefinition> tables = metadataExtractionService.extractTables(request.source(), request.tables());
        List<String> tableNames = new ArrayList<>();
        List<String> ddlStatements = new ArrayList<>();
        List<String> dataStatements = new ArrayList<>();
        List<String> indexStatements = new ArrayList<>();
        List<String> foreignKeyStatements = new ArrayList<>();

        for (TableDefinition table : tables) {
            tableNames.add(table.getName());
            log.info("生成表脚本: table={}, targetType={}", table.getName(), request.target().type());
            ddlStatements.add(targetDialect.createTable(table, targetSchema));

            if (includeData) {
                List<Map<String, Object>> rows = metadataExtractionService.fetchTableData(request.source(), table, sourceDialect);
                String insertStatements = targetDialect.createInsertStatements(table, rows, targetSchema);
                if (!insertStatements.isBlank()) {
                    dataStatements.add(insertStatements);
                }
            }

            indexStatements.addAll(targetDialect.createIndexes(table, targetSchema));
            foreignKeyStatements.addAll(targetDialect.createForeignKeys(table, targetSchema));
        }

        String ddlScript = joinSections(ddlStatements, indexStatements, foreignKeyStatements);
        String dataScript = String.join("\n\n", dataStatements);
        if (!ddlScript.isBlank()) {
            ddlScript = targetDialect.transformSql(ddlScript, targetSchema);
        }
        String fullScript = joinSections(ddlStatements, dataStatements, indexStatements, foreignKeyStatements);
        if (!fullScript.isBlank()) {
            String normalizedDdlAndStructure = joinSections(ddlStatements, indexStatements, foreignKeyStatements);
            if (!normalizedDdlAndStructure.isBlank()) {
                normalizedDdlAndStructure = targetDialect.transformSql(normalizedDdlAndStructure, targetSchema);
            }
            fullScript = joinSections(List.of(normalizedDdlAndStructure), dataStatements);
        }

        boolean executedOnTarget = false;
        if (Boolean.TRUE.equals(request.executeOnTarget())) {
            log.info("开始执行目标库脚本: targetType={}, statementCount={}",
                    request.target().type(), SqlStatementUtils.splitStatements(fullScript).size());
            executeScript(request, fullScript);
            executedOnTarget = true;
            log.info("目标库脚本执行完成: targetType={}", request.target().type());
        }

        log.info("数据库转换完成: tableCount={}, ddlLength={}, dataLength={}",
                tableNames.size(), ddlScript.length(), dataScript.length());
        return new ConversionResultResponse(tableNames, ddlScript, dataScript, fullScript, executedOnTarget);
    }

    private void executeScript(DatabaseConversionRequest request, String script) throws SQLException {
        try (Connection connection = jdbcConnectionService.openConnection(request.target());
             Statement statement = connection.createStatement()) {
            if (request.target().type() == DatabaseType.SQLSERVER || request.target().type() == DatabaseType.DM) {
                executeIdentityAwareScript(connection, statement, request, script);
                return;
            }
            for (String sql : SqlStatementUtils.splitStatements(script)) {
                statement.execute(sql);
            }
        }
    }

    private void executeIdentityAwareScript(Connection connection, Statement statement,
                                            DatabaseConversionRequest request, String script) throws SQLException {
        Map<String, Set<String>> identityColumnsCache = new HashMap<>();
        String activeIdentityTable = null;
        DatabaseType targetType = request.target().type();

        for (String sql : SqlStatementUtils.splitStatements(script)) {
            String trimmedSql = sql.trim();
            log.debug("执行 identity 感知 SQL: targetType={}, sql={}", targetType, abbreviateSql(trimmedSql));
            Matcher identityInsertMatcher = IDENTITY_INSERT_PATTERN.matcher(trimmedSql);
            if (identityInsertMatcher.matches()) {
                statement.execute(trimmedSql);
                activeIdentityTable = "ON".equalsIgnoreCase(identityInsertMatcher.group(2))
                        ? normalizeIdentifier(identityInsertMatcher.group(1))
                        : null;
                continue;
            }

            IdentityInsertInfo insertInfo = parseIdentityInsert(trimmedSql, request, connection, targetType);
            if (insertInfo != null) {
                Set<String> identityColumns = identityColumnsCache.computeIfAbsent(
                        insertInfo.tableKey(),
                        ignored -> loadIdentityColumns(connection, insertInfo)
                );

                boolean includesIdentityColumn = insertInfo.columnNames().stream().anyMatch(identityColumns::contains);
                if (includesIdentityColumn && !insertInfo.tableKey().equals(activeIdentityTable)) {
                    statement.execute(identityInsertSql(insertInfo.qualifiedTableName(), true));
                    try {
                        statement.execute(trimmedSql);
                    } finally {
                        statement.execute(identityInsertSql(insertInfo.qualifiedTableName(), false));
                    }
                    continue;
                }
            }

            statement.execute(trimmedSql);
        }
    }

    private IdentityInsertInfo parseIdentityInsert(String sql, DatabaseConversionRequest request,
                                                   Connection connection, DatabaseType targetType) throws SQLException {
        Matcher matcher = IDENTITY_AWARE_INSERT_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }

        String tableReference = matcher.group(1).trim();
        String columnList = matcher.group(2).trim();
        String[] rawColumns = columnList.split(",");
        Set<String> normalizedColumns = new HashSet<>();
        for (String rawColumn : rawColumns) {
            normalizedColumns.add(normalizeIdentifier(rawColumn));
        }

        String[] parts = splitIdentifierParts(tableReference);
        String catalog = connection.getCatalog();
        String schema = targetType == DatabaseType.DM
                ? resolveTargetSchema(request)
                : (request.target().schemaName() == null || request.target().schemaName().isBlank()
                ? connection.getSchema()
                : request.target().schemaName().trim());
        if (schema == null || schema.isBlank()) {
            schema = targetType == DatabaseType.SQLSERVER ? "dbo" : schema;
        }
        String tableName;

        if (parts.length >= 3) {
            catalog = parts[parts.length - 3];
            schema = parts[parts.length - 2];
            tableName = parts[parts.length - 1];
        } else if (parts.length == 2) {
            schema = parts[0];
            tableName = parts[1];
        } else if (parts.length == 1) {
            tableName = parts[0];
        } else {
            return null;
        }

        String qualifiedTableName = targetType == DatabaseType.SQLSERVER
                ? "[" + schema + "].[" + tableName + "]"
                : "\"" + schema + "\".\"" + tableName + "\"";
        String tableKey = normalizeIdentifier(schema + "." + tableName);
        return new IdentityInsertInfo(qualifiedTableName, tableKey, catalog, schema, tableName, normalizedColumns);
    }

    private Set<String> loadIdentityColumns(Connection connection, IdentityInsertInfo insertInfo) {
        Set<String> identityColumns = new HashSet<>();
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(
                    insertInfo.catalog(), insertInfo.schema(), insertInfo.tableName(), "%")) {
                while (columns.next()) {
                    if ("YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT"))) {
                        identityColumns.add(normalizeIdentifier(columns.getString("COLUMN_NAME")));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 SQL Server 目标表自增列信息失败: " + insertInfo.qualifiedTableName(), ex);
        }
        return identityColumns;
    }

    private String identityInsertSql(String qualifiedTableName, boolean on) {
        return "SET IDENTITY_INSERT " + qualifiedTableName + " " + (on ? "ON" : "OFF");
    }

    private String[] splitIdentifierParts(String identifier) {
        String normalized = identifier.trim()
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "");
        return normalized.split("\\.");
    }

    private String normalizeIdentifier(String identifier) {
        return identifier.trim()
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "")
                .toLowerCase();
    }

    private String abbreviateSql(String sql) {
        if (sql == null) {
            return null;
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.length() > 220 ? normalized.substring(0, 220) + " ..." : normalized;
    }

    private void validate(DatabaseConversionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Conversion request is required.");
        }
        if (request.source() == null) {
            throw new IllegalArgumentException("Source connection is required.");
        }
        if (request.target() == null) {
            throw new IllegalArgumentException("Target connection is required.");
        }
        if (request.source().type() == DatabaseType.DM
                && (request.source().schemaName() == null || request.source().schemaName().isBlank())) {
            throw new IllegalArgumentException("源数据库是 DM 时，必须填写源模式名(schemaName)。");
        }
        if (request.target().type() == DatabaseType.DM && resolveTargetSchema(request).isBlank()) {
            throw new IllegalArgumentException("目标数据库是 DM 时，必须填写目标模式名(schemaName/targetSchema)。");
        }
    }

    private String resolveTargetSchema(DatabaseConversionRequest request) {
        if (request.targetSchema() != null && !request.targetSchema().isBlank()) {
            return request.targetSchema().trim();
        }
        if (request.target() != null && request.target().schemaName() != null) {
            return request.target().schemaName().trim();
        }
        return "";
    }

    @SafeVarargs
    private final String joinSections(List<String>... sections) {
        List<String> blocks = new ArrayList<>();
        for (List<String> section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }
            String block = String.join("\n\n", section).trim();
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        return String.join("\n\n", blocks);
    }

    private record IdentityInsertInfo(
            String qualifiedTableName,
            String tableKey,
            String catalog,
            String schema,
            String tableName,
            Set<String> columnNames
    ) {
    }
}
