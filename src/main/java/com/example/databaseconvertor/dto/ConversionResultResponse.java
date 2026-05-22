package com.example.databaseconvertor.dto;

import java.util.List;

public record ConversionResultResponse(
        List<String> tables,
        String ddlScript,
        String dataScript,
        String fullScript,
        boolean executedOnTarget
) {
}
