package com.example.databaseconvertor.dto;

import com.example.databaseconvertor.dialect.DatabaseType;

public record SqlFileConversionResponse(
        DatabaseType targetType,
        String convertedSql
) {
}
