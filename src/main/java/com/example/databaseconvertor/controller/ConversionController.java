package com.example.databaseconvertor.controller;

import com.example.databaseconvertor.dialect.DatabaseType;
import com.example.databaseconvertor.dto.ConversionResultResponse;
import com.example.databaseconvertor.dto.DatabaseConversionRequest;
import com.example.databaseconvertor.dto.ConversionTaskStartResponse;
import com.example.databaseconvertor.dto.ConversionTaskStatusResponse;
import com.example.databaseconvertor.dto.SqlFileConversionResponse;
import com.example.databaseconvertor.service.DatabaseConversionService;
import com.example.databaseconvertor.service.DatabaseConversionTaskService;
import com.example.databaseconvertor.service.SqlFileConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;

@RestController
@RequestMapping("/api/conversions")
public class ConversionController {

    private static final Logger log = LoggerFactory.getLogger(ConversionController.class);

    private final DatabaseConversionService databaseConversionService;
    private final DatabaseConversionTaskService databaseConversionTaskService;
    private final SqlFileConversionService sqlFileConversionService;

    public ConversionController(DatabaseConversionService databaseConversionService,
                                DatabaseConversionTaskService databaseConversionTaskService,
                                SqlFileConversionService sqlFileConversionService) {
        this.databaseConversionService = databaseConversionService;
        this.databaseConversionTaskService = databaseConversionTaskService;
        this.sqlFileConversionService = sqlFileConversionService;
    }

    @PostMapping("/database")
    public ConversionResultResponse convertDatabase(@RequestBody DatabaseConversionRequest request) throws SQLException {
        log.info("收到数据库转换请求: sourceType={}, targetType={}, tablesCount={}, includeData={}, executeOnTarget={}",
                request.source() == null ? null : request.source().type(),
                request.target() == null ? null : request.target().type(),
                request.tables() == null ? 0 : request.tables().size(),
                request.includeData(),
                request.executeOnTarget());
        return databaseConversionService.convert(request);
    }

    @PostMapping("/database/tasks")
    public ConversionTaskStartResponse startDatabaseConversionTask(@RequestBody DatabaseConversionRequest request) {
        log.info("收到数据库异步转换请求: sourceType={}, targetType={}, tablesCount={}, includeData={}, executeOnTarget={}",
                request.source() == null ? null : request.source().type(),
                request.target() == null ? null : request.target().type(),
                request.tables() == null ? 0 : request.tables().size(),
                request.includeData(),
                request.executeOnTarget());
        return new ConversionTaskStartResponse(databaseConversionTaskService.submit(request));
    }

    @GetMapping("/database/tasks/{taskId}")
    public ConversionTaskStatusResponse getDatabaseConversionTask(@PathVariable String taskId) {
        return databaseConversionTaskService.getStatus(taskId);
    }

    @PostMapping(value = "/sql-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SqlFileConversionResponse convertSqlFile(@RequestPart("file") MultipartFile file,
                                                    @RequestParam("targetType") DatabaseType targetType,
                                                    @RequestParam(value = "targetSchema", required = false) String targetSchema)
            throws IOException {
        log.info("收到 SQL 文件转换请求: fileName={}, size={}, targetType={}, targetSchema={}",
                file == null ? null : file.getOriginalFilename(),
                file == null ? 0 : file.getSize(),
                targetType,
                targetSchema);
        String convertedSql = sqlFileConversionService.convert(file, targetType, targetSchema);
        return new SqlFileConversionResponse(targetType, convertedSql);
    }
}
