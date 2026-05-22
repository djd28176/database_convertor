package com.example.databaseconvertor.dialect;

import com.example.databaseconvertor.model.ColumnDefinition;
import com.example.databaseconvertor.model.IndexDefinition;
import com.example.databaseconvertor.model.TableDefinition;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialClob;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDialectTests {

    @Test
    void mysqlDialectShouldGenerateAutoIncrementColumn() {
        MySqlDialect dialect = new MySqlDialect();
        ColumnDefinition column = new ColumnDefinition();
        column.setName("id");
        column.setJdbcType(java.sql.Types.INTEGER);
        column.setSourceTypeName("INT");
        column.setSize(11);
        column.setNullable(false);
        column.setAutoIncrement(true);

        String definition = dialect.toColumnDefinition(column);
        assertTrue(definition.contains("AUTO_INCREMENT"));
        assertTrue(definition.contains("`id`"));
    }

    @Test
    void mysqlDialectShouldRenderClobContentInsteadOfDriverObjectToString() throws Exception {
        MySqlDialect dialect = new MySqlDialect();

        TableDefinition table = new TableDefinition();
        table.setName("aaaaaaaaa");

        ColumnDefinition id = new ColumnDefinition();
        id.setName("id");
        id.setJdbcType(java.sql.Types.BIGINT);
        table.getColumns().add(id);

        ColumnDefinition text = new ColumnDefinition();
        text.setName("code_ts_desc");
        text.setJdbcType(java.sql.Types.CLOB);
        table.getColumns().add(text);

        String sql = dialect.createInsertStatements(
                table,
                List.of(Map.of("id", 1L, "code_ts_desc", new SerialClob("真实文本".toCharArray()))),
                "dec_experience"
        );

        assertTrue(sql.contains("'真实文本'"));
        assertTrue(!sql.contains("SerialClob"));
    }

    @Test
    void mysqlDialectShouldNotPrefixSchemaInGeneratedSql() {
        MySqlDialect dialect = new MySqlDialect();
        TableDefinition table = new TableDefinition();
        table.setSchemaName("TEST");
        table.setName("aaaaaaaaa");

        ColumnDefinition id = new ColumnDefinition();
        id.setName("id");
        id.setJdbcType(java.sql.Types.BIGINT);
        id.setSourceTypeName("BIGINT");
        id.setAutoIncrement(true);
        id.setNullable(false);
        table.getColumns().add(id);
        table.getPrimaryKeys().add("id");

        String createSql = dialect.createTable(table, "TEST");
        assertTrue(createSql.contains("CREATE TABLE `aaaaaaaaa`"));
        assertTrue(!createSql.contains("`TEST`.`aaaaaaaaa`"));
    }

    @Test
    void sqlServerDialectShouldConvertMysqlIdentitySyntax() {
        SqlServerDialect dialect = new SqlServerDialect();
        String converted = dialect.transformSql("""
                CREATE TABLE `user_info` (
                  `id` INT AUTO_INCREMENT PRIMARY KEY,
                  `name` VARCHAR(100)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

        assertTrue(converted.contains("[id] INT IDENTITY(1,1)"));
        assertTrue(converted.contains("[user_info]"));
    }

    @Test
    void sqlServerDialectShouldWrapIdentityInsertWhenMigratingData() {
        SqlServerDialect dialect = new SqlServerDialect();

        TableDefinition table = new TableDefinition();
        table.setSchemaName("dbo");
        table.setName("user_info");

        ColumnDefinition id = new ColumnDefinition();
        id.setName("id");
        id.setJdbcType(java.sql.Types.BIGINT);
        id.setSourceTypeName("BIGINT");
        id.setAutoIncrement(true);
        table.getColumns().add(id);

        ColumnDefinition name = new ColumnDefinition();
        name.setName("name");
        name.setJdbcType(java.sql.Types.VARCHAR);
        name.setSourceTypeName("VARCHAR");
        table.getColumns().add(name);

        String sql = dialect.createInsertStatements(table, List.of(Map.of("id", 1L, "name", "Alice")), "dbo");
        assertTrue(sql.contains("SET IDENTITY_INSERT [dbo].[user_info] ON;"));
        assertTrue(sql.contains("INSERT INTO [dbo].[user_info]"));
        assertTrue(sql.contains("SET IDENTITY_INSERT [dbo].[user_info] OFF;"));
    }

    @Test
    void dmDialectShouldCreateQualifiedTable() {
        DmDialect dialect = new DmDialect();
        TableDefinition table = new TableDefinition();
        table.setSchemaName("TEST");
        table.setName("order_info");

        ColumnDefinition id = new ColumnDefinition();
        id.setName("id");
        id.setJdbcType(java.sql.Types.BIGINT);
        id.setSourceTypeName("BIGINT");
        id.setNullable(false);
        id.setAutoIncrement(true);
        table.getColumns().add(id);
        table.getPrimaryKeys().add("id");

        String sql = dialect.createTable(table, "TARGET");
        assertTrue(sql.contains("CREATE TABLE \"TARGET\".\"order_info\""));
        assertTrue(sql.contains("IDENTITY(1,1)"));
    }

    @Test
    void dmDialectShouldWrapIdentityInsertWhenMigratingData() {
        DmDialect dialect = new DmDialect();

        TableDefinition table = new TableDefinition();
        table.setSchemaName("TEST");
        table.setName("order_info");

        ColumnDefinition id = new ColumnDefinition();
        id.setName("id");
        id.setJdbcType(java.sql.Types.BIGINT);
        id.setSourceTypeName("BIGINT");
        id.setAutoIncrement(true);
        table.getColumns().add(id);

        ColumnDefinition name = new ColumnDefinition();
        name.setName("name");
        name.setJdbcType(java.sql.Types.VARCHAR);
        name.setSourceTypeName("VARCHAR");
        table.getColumns().add(name);

        String sql = dialect.createInsertStatements(table, List.of(Map.of("id", 1L, "name", "Alice")), "TARGET");
        assertTrue(sql.contains("SET IDENTITY_INSERT \"TARGET\".\"order_info\" ON;"));
        assertTrue(sql.contains("INSERT INTO \"TARGET\".\"order_info\""));
        assertTrue(sql.contains("SET IDENTITY_INSERT \"TARGET\".\"order_info\" OFF;"));
    }

    @Test
    void dmDialectShouldRewriteMysqlCommentsAndCharsets() {
        DmDialect dialect = new DmDialect();
        String converted = dialect.transformSql("""
                create table dec_head
                (
                    entry_id varchar(18) charset utf8mb4 null comment '海关编号',
                    last_note varchar(16383) null comment '最后一个回执的说明'
                )
                comment '进口/出口报关单表头' charset = utf8;
                """);

        assertTrue(converted.contains("CREATE TABLE dec_head"));
        assertTrue(converted.contains("entry_id VARCHAR2(18) null"));
        assertTrue(converted.contains("last_note CLOB null"));
        assertTrue(converted.contains("COMMENT ON TABLE dec_head IS '进口/出口报关单表头';"));
        assertTrue(converted.contains("COMMENT ON COLUMN dec_head.entry_id IS '海关编号';"));
        assertTrue(!converted.contains("charset"));
        assertTrue(!converted.contains(" comment '"));
    }

    @Test
    void dmDialectShouldApplyTargetSchemaToQualifiedStatements() {
        DmDialect dialect = new DmDialect();
        String converted = dialect.transformSql("""
                insert into smart_supervise.dec_head (guid) values ('1');
                create unique index uq_test on smart_supervise.dec_head (guid);
                """, "CUSTOMS");

        assertTrue(converted.contains("insert into CUSTOMS.dec_head"));
        assertTrue(converted.contains("create unique index uq_test on CUSTOMS.dec_head"));
        assertTrue(!converted.contains("smart_supervise.dec_head"));
    }

    @Test
    void dmDialectShouldAvoidDuplicateIndexNamesAcrossTables() {
        DmDialect dialect = new DmDialect();

        TableDefinition firstTable = new TableDefinition();
        firstTable.setName("hs_node");
        IndexDefinition firstIndex = new IndexDefinition();
        firstIndex.setName("uq_hs_node_multi_fields");
        firstIndex.setUnique(true);
        firstIndex.getColumns().addAll(List.of("start_chapter_no", "end_chapter_no"));
        firstTable.getIndexes().add(firstIndex);

        TableDefinition secondTable = new TableDefinition();
        secondTable.setName("hs_node_old");
        IndexDefinition secondIndex = new IndexDefinition();
        secondIndex.setName("uq_hs_node_multi_fields");
        secondIndex.setUnique(true);
        secondIndex.getColumns().addAll(List.of("start_chapter_no", "end_chapter_no"));
        secondTable.getIndexes().add(secondIndex);

        List<String> firstSql = dialect.createIndexes(firstTable, "TEST");
        List<String> secondSql = dialect.createIndexes(secondTable, "TEST");

        assertTrue(firstSql.get(0).contains("\"uq_hs_node_multi_fields_hs_node\""));
        assertTrue(secondSql.get(0).contains("\"uq_hs_node_multi_fields_hs_node_old\""));
    }
}
