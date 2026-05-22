package com.example.databaseconvertor.service;

import com.example.databaseconvertor.dto.DbConnectionRequest;
import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.TableDefinition;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataDictionaryService {

    private static final Logger log = LoggerFactory.getLogger(DataDictionaryService.class);

    private static final String TABLE_NAME_CN = "数据表（中文）";
    private static final String TABLE_NAME_EN = "数据表（英文）";
    private static final String DATA_TYPE = "数据类型";
    private static final String COLUMN_NAME_EN = "字段名称（英文）";
    private static final String COLUMN_NAME_CN = "字段名称（中文）";

    private final MetadataExtractionService metadataExtractionService;
    private final SqlMetadataParserService sqlMetadataParserService;

    public DataDictionaryService(MetadataExtractionService metadataExtractionService,
                                 SqlMetadataParserService sqlMetadataParserService) {
        this.metadataExtractionService = metadataExtractionService;
        this.sqlMetadataParserService = sqlMetadataParserService;
    }

    public GeneratedDictionary generateFromDatabase(MultipartFile template,
                                                    DbConnectionRequest source,
                                                    List<String> selectedTables) throws Exception {
        validateTemplate(template);
        List<TableDefinition> tables = metadataExtractionService.extractTables(source, selectedTables);
        log.info("开始根据数据库生成数据字典: dbType={}, tableCount={}",
                source == null ? null : source.type(), tables.size());
        return buildWorkbook(template, tables);
    }

    public GeneratedDictionary generateFromSql(MultipartFile template, MultipartFile sqlFile) throws Exception {
        validateTemplate(template);
        if (sqlFile == null || sqlFile.isEmpty()) {
            throw new IllegalArgumentException("请上传建表 SQL 文件。");
        }

        String sql = new String(sqlFile.getBytes(), StandardCharsets.UTF_8);
        List<TableDefinition> tables = sqlMetadataParserService.parse(sql);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("没有从 SQL 中解析到可用的 CREATE TABLE 语句。");
        }
        log.info("开始根据 SQL 文件生成数据字典: fileName={}, tableCount={}",
                sqlFile.getOriginalFilename(), tables.size());
        return buildWorkbook(template, tables);
    }

    private GeneratedDictionary buildWorkbook(MultipartFile template, List<TableDefinition> tables) throws IOException {
        try (InputStream inputStream = template.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            TemplateLocation templateLocation = findTemplateLocation(workbook);
            List<DictionaryRow> rows = flattenRows(tables);
            populateRows(templateLocation, rows);
            workbook.write(outputStream);

            String extension = resolveExtension(template.getOriginalFilename());
            String fileName = "data-dictionary-filled." + extension;
            log.info("数据字典生成完成: rowCount={}, fileName={}", rows.size(), fileName);
            return new GeneratedDictionary(fileName, outputStream.toByteArray());
        }
    }

    private void validateTemplate(MultipartFile template) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("请上传 Excel 模板文件。");
        }
    }

    private TemplateLocation findTemplateLocation(Workbook workbook) {
        Set<String> requiredHeaders = Set.of(
                normalizeHeader(TABLE_NAME_CN),
                normalizeHeader(TABLE_NAME_EN),
                normalizeHeader(DATA_TYPE),
                normalizeHeader(COLUMN_NAME_EN),
                normalizeHeader(COLUMN_NAME_CN)
        );

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                Map<String, Integer> headerIndexes = new LinkedHashMap<>();
                for (Cell cell : row) {
                    String normalized = normalizeHeader(cell.toString());
                    if (requiredHeaders.contains(normalized)) {
                        headerIndexes.put(normalized, cell.getColumnIndex());
                    }
                }
                if (headerIndexes.keySet().containsAll(requiredHeaders)) {
                    Row templateRow = sheet.getRow(row.getRowNum() + 1);
                    return new TemplateLocation(sheet, row.getRowNum(), headerIndexes, templateRow);
                }
            }
        }
        throw new IllegalArgumentException("在 Excel 模板中没有找到需要填写的表头列。");
    }

    private void populateRows(TemplateLocation templateLocation, List<DictionaryRow> rows) {
        Sheet sheet = templateLocation.sheet();
        int startRowIndex = templateLocation.headerRowIndex() + 1;
        int existingLastRow = Math.max(sheet.getLastRowNum(), startRowIndex - 1);
        int targetLastRow = startRowIndex + Math.max(rows.size() - 1, 0);

        Row templateRow = templateLocation.templateRow();
        for (int i = 0; i < rows.size(); i++) {
            Row row = sheet.getRow(startRowIndex + i);
            if (row == null) {
                row = sheet.createRow(startRowIndex + i);
            }
            applyRowStyle(templateRow, row);
            DictionaryRow dictionaryRow = rows.get(i);
            writeCell(row, templateLocation.columnIndex(TABLE_NAME_CN), dictionaryRow.tableNameCn(), templateRow);
            writeCell(row, templateLocation.columnIndex(TABLE_NAME_EN), dictionaryRow.tableNameEn(), templateRow);
            writeCell(row, templateLocation.columnIndex(DATA_TYPE), dictionaryRow.dataType(), templateRow);
            writeCell(row, templateLocation.columnIndex(COLUMN_NAME_EN), dictionaryRow.columnNameEn(), templateRow);
            writeCell(row, templateLocation.columnIndex(COLUMN_NAME_CN), dictionaryRow.columnNameCn(), templateRow);
        }

        for (int rowIndex = targetLastRow + 1; rowIndex <= existingLastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                clearRow(row);
            }
        }
    }

    private void applyRowStyle(Row templateRow, Row targetRow) {
        if (templateRow == null) {
            return;
        }
        targetRow.setHeight(templateRow.getHeight());
    }

    private void writeCell(Row row, int columnIndex, String value, Row templateRow) {
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            cell = row.createCell(columnIndex);
        }
        if (templateRow != null) {
            Cell templateCell = templateRow.getCell(columnIndex);
            if (templateCell != null) {
                CellStyle style = templateCell.getCellStyle();
                if (style != null) {
                    cell.setCellStyle(style);
                }
            }
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private void clearRow(Row row) {
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum < 0) {
            return;
        }
        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = row.getCell(i);
            if (cell != null) {
                cell.setBlank();
            }
        }
    }

    private List<DictionaryRow> flattenRows(List<TableDefinition> tables) {
        List<DictionaryRow> rows = new ArrayList<>();
        for (TableDefinition table : tables) {
            for (ColumnDefinition column : table.getColumns()) {
                rows.add(new DictionaryRow(
                        safeValue(table.getRemarks()),
                        safeValue(table.getName()),
                        formatDataType(column),
                        safeValue(column.getName()),
                        safeValue(column.getRemarks())
                ));
            }
        }
        return rows;
    }

    private String formatDataType(ColumnDefinition column) {
        String sourceType = safeValue(column.getSourceTypeName()).trim();
        if (!sourceType.isBlank()) {
            return sourceType;
        }
        return switch (column.getJdbcType()) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER -> "INT";
            case Types.DECIMAL, Types.NUMERIC -> column.getSize() > 0
                    ? "DECIMAL(" + column.getSize() + ", " + column.getScale() + ")"
                    : "DECIMAL";
            case Types.DATE -> "DATE";
            case Types.TIMESTAMP -> "DATETIME";
            case Types.CLOB -> "TEXT";
            default -> column.getSize() > 0 ? "VARCHAR(" + column.getSize() + ")" : "VARCHAR";
        };
    }

    private String resolveExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "xls";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    public record GeneratedDictionary(String fileName, byte[] content) {
    }

    private record TemplateLocation(Sheet sheet,
                                    int headerRowIndex,
                                    Map<String, Integer> headerIndexes,
                                    Row templateRow) {
        private int columnIndex(String headerName) {
            Integer index = headerIndexes.get(headerName.replaceAll("\\s+", ""));
            if (index == null) {
                throw new IllegalArgumentException("模板缺少列: " + headerName);
            }
            return index;
        }
    }

    private record DictionaryRow(String tableNameCn,
                                 String tableNameEn,
                                 String dataType,
                                 String columnNameEn,
                                 String columnNameCn) {
    }
}
