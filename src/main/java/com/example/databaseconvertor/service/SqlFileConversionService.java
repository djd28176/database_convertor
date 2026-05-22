package com.example.databaseconvertor.service;

import com.example.databaseconvertor.dialect.DatabaseDialect;
import com.example.databaseconvertor.dialect.DatabaseDialectFactory;
import com.example.databaseconvertor.dialect.DatabaseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class SqlFileConversionService {

    private static final Logger log = LoggerFactory.getLogger(SqlFileConversionService.class);

    private final DatabaseDialectFactory dialectFactory;

    public SqlFileConversionService(DatabaseDialectFactory dialectFactory) {
        this.dialectFactory = dialectFactory;
    }

    public String convert(MultipartFile file, DatabaseType targetType, String targetSchema) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("SQL file is required.");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Target database type is required.");
        }
        if (targetType == DatabaseType.DM && (targetSchema == null || targetSchema.isBlank())) {
            throw new IllegalArgumentException("目标数据库是 DM 时，必须填写目标模式名(schemaName)。");
        }

        DatabaseDialect targetDialect = dialectFactory.getDialect(targetType);
        String sql = new String(file.getBytes(), StandardCharsets.UTF_8);
        log.info("开始转换 SQL 文件: fileName={}, targetType={}, targetSchema={}, contentLength={}",
                file.getOriginalFilename(), targetType, targetSchema, sql.length());
        String converted = targetDialect.transformSql(sql, targetSchema).trim();
        log.info("SQL 文件转换完成: fileName={}, targetType={}, resultLength={}",
                file.getOriginalFilename(), targetType, converted.length());
        return converted;
    }
}
