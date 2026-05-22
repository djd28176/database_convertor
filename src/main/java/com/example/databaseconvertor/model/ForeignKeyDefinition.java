package com.example.databaseconvertor.model;

import java.util.ArrayList;
import java.util.List;

public class ForeignKeyDefinition {

    private String name;
    private String referencedSchema;
    private String referencedTable;
    private final List<String> columnNames = new ArrayList<>();
    private final List<String> referencedColumnNames = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReferencedSchema() {
        return referencedSchema;
    }

    public void setReferencedSchema(String referencedSchema) {
        this.referencedSchema = referencedSchema;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public void setReferencedTable(String referencedTable) {
        this.referencedTable = referencedTable;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<String> getReferencedColumnNames() {
        return referencedColumnNames;
    }
}
