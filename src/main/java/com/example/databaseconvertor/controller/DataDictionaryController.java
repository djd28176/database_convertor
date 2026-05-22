package com.example.databaseconvertor.controller;

import com.example.databaseconvertor.dto.DbConnectionRequest;
import com.example.databaseconvertor.service.DataDictionaryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/dictionaries")
public class DataDictionaryController {

    private static final Logger log = LoggerFactory.getLogger(DataDictionaryController.class);

    private final DataDictionaryService dataDictionaryService;
    private final ObjectMapper objectMapper;

    public DataDictionaryController(DataDictionaryService dataDictionaryService, ObjectMapper objectMapper) {
        this.dataDictionaryService = dataDictionaryService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/database", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateFromDatabase(@RequestPart("template") MultipartFile template,
                                                       @RequestPart("source") String sourceJson,
                                                       @RequestPart(value = "tables", required = false) String tablesJson)
            throws Exception {
        DbConnectionRequest source = objectMapper.readValue(sourceJson, DbConnectionRequest.class);
        List<String> tables = tablesJson == null || tablesJson.isBlank()
                ? List.of()
                : objectMapper.readValue(tablesJson, new TypeReference<>() { });
        log.info("收到数据字典生成请求(数据库): dbType={}, selectedTables={}, template={}",
                source.type(), tables.size(), template.getOriginalFilename());
        DataDictionaryService.GeneratedDictionary dictionary = dataDictionaryService.generateFromDatabase(
                template, source, tables);
        return buildResponse(dictionary);
    }

    @PostMapping(value = "/sql-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateFromSql(@RequestPart("template") MultipartFile template,
                                                  @RequestPart("sqlFile") MultipartFile sqlFile) throws Exception {
        log.info("收到数据字典生成请求(SQL): template={}, sqlFile={}",
                template.getOriginalFilename(), sqlFile.getOriginalFilename());
        DataDictionaryService.GeneratedDictionary dictionary = dataDictionaryService.generateFromSql(template, sqlFile);
        return buildResponse(dictionary);
    }

    private ResponseEntity<byte[]> buildResponse(DataDictionaryService.GeneratedDictionary dictionary) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(dictionary.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                .body(dictionary.content());
    }
}
