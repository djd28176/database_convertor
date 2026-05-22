package com.example.databaseconvertor.dto;

import com.example.databaseconvertor.dialect.DatabaseType;

public record DbConnectionRequest(
        DatabaseType type,
        String jdbcUrl,
        String driverClassName,
        String host,
        Integer port,
        String databaseName,
        String schemaName,
        String username,
        String password
) {
}
