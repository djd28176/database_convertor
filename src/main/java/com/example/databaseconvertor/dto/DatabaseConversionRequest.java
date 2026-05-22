package com.example.databaseconvertor.dto;

import java.util.List;

public record DatabaseConversionRequest(
        DbConnectionRequest source,
        DbConnectionRequest target,
        List<String> tables,
        Boolean includeData,
        Boolean executeOnTarget,
        String targetSchema
) {
}
