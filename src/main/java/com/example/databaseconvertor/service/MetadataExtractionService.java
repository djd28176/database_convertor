package com.example.databaseconvertor.service;

import com.example.databaseconvertor.dialect.DatabaseDialect;
import com.example.databaseconvertor.dialect.DatabaseDialectFactory;
import com.example.databaseconvertor.dto.DbConnectionRequest;
import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.ForeignKeyDefinition;
import com.example.databaseconvertor.model.IndexDefinition;
import com.example.databaseconvertor.model.TableDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

@Service
public class MetadataExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MetadataExtractionService.class);

    private final JdbcConnectionService jdbcConnectionService;
    private final DatabaseDialectFactory dialectFactory;

    public MetadataExtractionService(JdbcConnectionService jdbcConnectionService, DatabaseDialectFactory dialectFactory) {
        this.jdbcConnectionService = jdbcConnectionService;
        this.dialectFactory = dialectFactory;
    }

    public List<String> listTables(DbConnectionRequest request) throws SQLException {
        try (Connection connection = jdbcConnectionService.openConnection(request)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(request, connection);
            String schema = resolveSchema(request, connection);
            log.info("开始读取表列表: dbType={}, catalog={}, schema={}", request.type(), catalog, schema);
            try (ResultSet resultSet = metaData.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                Set<String> tables = new LinkedHashSet<>();
                while (resultSet.next()) {
                    tables.add(resultSet.getString("TABLE_NAME"));
                }
                log.info("表列表读取完成: dbType={}, tableCount={}", request.type(), tables.size());
                return new ArrayList<>(tables);
            }
        }
    }

    public List<TableDefinition> extractTables(DbConnectionRequest request, List<String> selectedTables) throws SQLException {
        DatabaseDialect dialect = dialectFactory.getDialect(request.type());
        try (Connection connection = jdbcConnectionService.openConnection(request)) {
            List<String> tables = selectedTables == null || selectedTables.isEmpty()
                    ? listTablesFromConnection(request, connection)
                    : selectedTables;
            log.info("开始提取表结构: dbType={}, tableCount={}", request.type(), tables.size());
            List<TableDefinition> tableDefinitions = new ArrayList<>();
            for (String tableName : tables) {
                tableDefinitions.add(extractTable(connection, request, dialect, tableName));
            }
            log.info("表结构提取完成: dbType={}, extractedTableCount={}", request.type(), tableDefinitions.size());
            return tableDefinitions;
        }
    }

    public List<Map<String, Object>> fetchTableData(DbConnectionRequest request, TableDefinition table, DatabaseDialect dialect)
            throws SQLException {
        return fetchTableData(request, table, dialect, null);
    }

    public List<Map<String, Object>> fetchTableData(DbConnectionRequest request, TableDefinition table,
                                                    DatabaseDialect dialect,
                                                    RowFetchProgressListener progressListener)
            throws SQLException {
        try (Connection connection = jdbcConnectionService.openConnection(request)) {
            return fetchTableData(connection, request, table, dialect, progressListener);
        }
    }

    public List<Map<String, Object>> fetchTableData(Connection connection,
                                                    DbConnectionRequest request,
                                                    TableDefinition table,
                                                    DatabaseDialect dialect,
                                                    RowFetchProgressListener progressListener)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM "
                     + dialect.qualifyName(table.getSchemaName(), table.getName()))) {
            log.info("开始读取表数据: dbType={}, table={}", request.type(), dialect.qualifyName(table.getSchemaName(), table.getName()));
            List<Map<String, Object>> rows = new ArrayList<>();
            int rowCount = 0;
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (ColumnDefinition column : table.getColumns()) {
                    row.put(column.getName(), normalizeJdbcValue(resultSet.getObject(column.getName())));
                }
                rows.add(row);
                rowCount++;
                if (progressListener != null && rowCount % 200 == 0) {
                    progressListener.onProgress(rowCount);
                }
            }
            if (progressListener != null) {
                progressListener.onProgress(rowCount);
            }
            log.info("表数据读取完成: table={}, rowCount={}", table.getName(), rows.size());
            return rows;
        }
    }

    @FunctionalInterface
    public interface RowFetchProgressListener {
        void onProgress(int rowCount);
    }

    private List<String> listTablesFromConnection(DbConnectionRequest request, Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = resolveCatalog(request, connection);
        String schema = resolveSchema(request, connection);
        List<String> tables = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private TableDefinition extractTable(Connection connection, DbConnectionRequest request,
                                         DatabaseDialect dialect, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = resolveCatalog(request, connection);
        String schema = resolveSchema(request, connection);
        String actualSchema = schema;
        TableDefinition table = new TableDefinition();

        try (ResultSet tableResult = metaData.getTables(catalog, schema, tableName, new String[]{"TABLE"})) {
            if (tableResult.next()) {
                if (tableResult.getString("TABLE_SCHEM") != null) {
                    actualSchema = tableResult.getString("TABLE_SCHEM");
                }
                table.setRemarks(tableResult.getString("REMARKS"));
            }
        }

        table.setName(tableName);
        table.setSchemaName(actualSchema);

        try (ResultSet columns = metaData.getColumns(catalog, actualSchema, tableName, "%")) {
            while (columns.next()) {
                ColumnDefinition column = new ColumnDefinition();
                column.setName(columns.getString("COLUMN_NAME"));
                column.setJdbcType(columns.getInt("DATA_TYPE"));
                column.setSourceTypeName(columns.getString("TYPE_NAME"));
                column.setSize(columns.getInt("COLUMN_SIZE"));
                column.setScale(columns.getInt("DECIMAL_DIGITS"));
                column.setNullable(columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                column.setDefaultValue(columns.getString("COLUMN_DEF"));
                column.setAutoIncrement("YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT")));
                column.setRemarks(columns.getString("REMARKS"));
                table.getColumns().add(column);
            }
        }

        try (ResultSet primaryKeys = metaData.getPrimaryKeys(catalog, actualSchema, tableName)) {
            while (primaryKeys.next()) {
                table.getPrimaryKeys().add(primaryKeys.getString("COLUMN_NAME"));
            }
        }

        Map<String, IndexDefinition> indexes = new LinkedHashMap<>();
        try (ResultSet indexResult = metaData.getIndexInfo(catalog, actualSchema, tableName, false, false)) {
            while (indexResult.next()) {
                String indexName = indexResult.getString("INDEX_NAME");
                String columnName = indexResult.getString("COLUMN_NAME");
                if (indexName == null || columnName == null || "PRIMARY".equalsIgnoreCase(indexName)) {
                    continue;
                }
                IndexDefinition index = indexes.get(indexName);
                if (index == null) {
                    index = new IndexDefinition();
                    index.setName(indexName);
                    index.setUnique(!indexResult.getBoolean("NON_UNIQUE"));
                    indexes.put(indexName, index);
                }
                index.getColumns().add(columnName);
            }
        }
        table.getIndexes().addAll(indexes.values());

        Map<String, ForeignKeyDefinition> foreignKeys = new LinkedHashMap<>();
        try (ResultSet fkResult = metaData.getImportedKeys(catalog, actualSchema, tableName)) {
            while (fkResult.next()) {
                String fkName = fkResult.getString("FK_NAME");
                String key = fkName == null || fkName.isBlank()
                        ? tableName + "_" + fkResult.getString("PKTABLE_NAME") + "_FK"
                        : fkName;
                ForeignKeyDefinition foreignKey = foreignKeys.get(key);
                if (foreignKey == null) {
                    foreignKey = new ForeignKeyDefinition();
                    foreignKey.setName(key);
                    foreignKey.setReferencedSchema(fkResult.getString("PKTABLE_SCHEM"));
                    foreignKey.setReferencedTable(fkResult.getString("PKTABLE_NAME"));
                    foreignKeys.put(key, foreignKey);
                }
                foreignKey.getColumnNames().add(fkResult.getString("FKCOLUMN_NAME"));
                foreignKey.getReferencedColumnNames().add(fkResult.getString("PKCOLUMN_NAME"));
            }
        }
        table.getForeignKeys().addAll(foreignKeys.values());

        if (table.getColumns().isEmpty()) {
            throw new IllegalArgumentException("Table not found or has no readable columns: "
                    + dialect.qualifyName(actualSchema, tableName));
        }

        log.info("提取单表完成: table={}, schema={}, columnCount={}, pkCount={}, indexCount={}, fkCount={}",
                table.getName(),
                table.getSchemaName(),
                table.getColumns().size(),
                table.getPrimaryKeys().size(),
                table.getIndexes().size(),
                table.getForeignKeys().size());

        return table;
    }

    private String resolveCatalog(DbConnectionRequest request, Connection connection) throws SQLException {
        if (request.type() == null) {
            return null;
        }
        if (request.type().name().equals("MYSQL") || request.type().name().equals("SQLSERVER")) {
            if (request.databaseName() != null && !request.databaseName().isBlank()) {
                return request.databaseName();
            }
            return connection.getCatalog();
        }
        return null;
    }

    private String resolveSchema(DbConnectionRequest request, Connection connection) throws SQLException {
        if (request.schemaName() != null && !request.schemaName().isBlank()) {
            return request.schemaName();
        }
        return switch (request.type()) {
            case MYSQL -> null;
            case SQLSERVER -> connection.getSchema() == null ? "dbo" : connection.getSchema();
            case DM -> connection.getSchema();
        };
    }

    private Object normalizeJdbcValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof NClob nClob) {
            return readClob(nClob);
        }
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof Blob blob) {
            return readBlob(blob);
        }
        return value;
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
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Failed to read BLOB value.", ex);
        }
    }
}
