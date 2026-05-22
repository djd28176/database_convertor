package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.TableDefinition;

import java.util.List;
import java.util.Map;

public interface DatabaseDialect {

    DatabaseType getType();

    String quoteIdentifier(String identifier);

    String qualifyName(String schemaName, String tableName);

    String toColumnDefinition(ColumnDefinition column);

    String createTable(TableDefinition table, String targetSchema);

    List<String> createIndexes(TableDefinition table, String targetSchema);

    List<String> createForeignKeys(TableDefinition table, String targetSchema);

    String createInsertStatements(TableDefinition table, List<Map<String, Object>> rows, String targetSchema);

    String literal(Object value);

    String transformSql(String sql);

    default String transformSql(String sql, String targetSchema) {
        return transformSql(sql);
    }
}
