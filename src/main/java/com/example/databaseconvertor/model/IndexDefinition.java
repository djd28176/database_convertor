package com.example.databaseconvertor.model;

import java.util.ArrayList;
import java.util.List;

public class IndexDefinition {

    private String name;
    private boolean unique;
    private final List<String> columns = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public List<String> getColumns() {
        return columns;
    }
}
