package com.example.databaseconvertor.controller;

import com.example.databaseconvertor.dto.DbConnectionRequest;
import com.example.databaseconvertor.dto.TableListResponse;
import com.example.databaseconvertor.service.MetadataExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private static final Logger log = LoggerFactory.getLogger(MetadataController.class);

    private final MetadataExtractionService metadataExtractionService;

    public MetadataController(MetadataExtractionService metadataExtractionService) {
        this.metadataExtractionService = metadataExtractionService;
    }

    @PostMapping("/tables")
    public TableListResponse listTables(@RequestBody DbConnectionRequest request) throws SQLException {
        log.info("收到表列表读取请求: dbType={}, host={}, databaseName={}, schemaName={}",
                request.type(), request.host(), request.databaseName(), request.schemaName());
        return new TableListResponse(metadataExtractionService.listTables(request));
    }
}
