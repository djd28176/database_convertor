package com.example.databaseconvertor.model;

import java.util.ArrayList;
import java.util.List;

public class TableDefinition {

    private String schemaName;
    private String name;
    private String remarks;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<String> primaryKeys = new ArrayList<>();
    private final List<IndexDefinition> indexes = new ArrayList<>();
    private final List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    public List<IndexDefinition> getIndexes() {
        return indexes;
    }

    public List<ForeignKeyDefinition> getForeignKeys() {
        return foreignKeys;
    }
}
