# 数据库转换项目

这是一个基于 Java 17 + Spring Boot 的数据库转换服务，面向以下 3 种数据库之间的结构和 SQL 方言转换：

- MySQL
- SQL Server
- 达梦 DM

当前版本已经实现你提到的两个核心能力：

1. 配置源库和目标库，选择源库中的表，生成目标库兼容的建表 SQL；可选择是否包含数据。
2. 上传 `.sql` 文件，按目标数据库类型做方言转换。

## 功能说明

### 1. 数据库到数据库转换

接口会读取源数据库元数据，提取：

- 表
- 字段
- 主键
- 普通索引 / 唯一索引
- 外键
- 可选的数据行

然后生成目标数据库兼容的 SQL 脚本。

支持两种使用方式：

- 只生成 SQL 脚本
- 生成后直接执行到目标数据库

### 2. SQL 文件转换

上传 SQL 文件后，服务会对常见方言差异做转换，例如：

- 标识符引号风格
- `AUTO_INCREMENT` / `IDENTITY(1,1)` 互转
- `VARCHAR2` / `VARCHAR` / `NVARCHAR` / `TEXT` / `CLOB`
- `DATETIME` / `DATETIME2` / `TIMESTAMP`
- `NUMBER` / `DECIMAL`
- MySQL 的 `ENGINE`、`CHARSET`、`COLLATE`

如果目标数据库是 DM：

- 必须填写目标模式名 `schemaName`
- 程序会把 SQL 中原有的 `库名.表名` 改写成你填写的 `模式名.表名`
- 表注释和字段注释会改写成 `COMMENT ON TABLE / COMMENT ON COLUMN`

说明：
这是“通用 SQL 转换器”，对常见建表 SQL 和常规 DML 有较好支持；如果 SQL 文件里包含存储过程、函数、触发器、分区表、数据库厂商私有语法，仍然建议按业务场景继续增强规则。

## 项目结构

```text
src/main/java/com/example/databaseconvertor
├── controller    # REST 接口
├── dialect       # 数据库方言与类型映射
├── dto           # 请求 / 响应对象
├── model         # 表结构模型
├── service       # 元数据读取、脚本生成、SQL 文件转换
└── util          # SQL 语句拆分等工具
```

## 启动要求

- JDK 17
- Maven 3.9+（本机如果没有 `mvn`，可以用 IDE 直接以 Maven 项目导入，或自行安装 Maven）

## 依赖说明

项目已内置：

- MySQL JDBC 驱动
- SQL Server JDBC 驱动

DM 驱动通常不在公共仓库统一分发，因此这里采用“运行时自行提供”的方式：

- 默认驱动类：`dm.jdbc.driver.DmDriver`
- 如果运行时报 `JDBC driver not found`，请把达梦 JDBC jar 放到项目根目录的 `drivers/` 目录下
- 程序也支持通过环境变量 `DM_JDBC_DIR` 指向驱动目录，或通过 `DM_JDBC_JAR` 指向单个 jar 文件
- 也可以在请求里传 `driverClassName`

例如：

```text
database_convertor/
├── drivers/
│   └── DmJdbcDriver18.jar
└── src/
```

## 运行

```bash
mvn spring-boot:run
```

启动后访问：

- [http://localhost:8080](http://localhost:8080)

## 接口示例

### 1. 获取源库表列表

`POST /api/metadata/tables`

```json
{
  "type": "MYSQL",
  "host": "127.0.0.1",
  "port": 3306,
  "databaseName": "demo",
  "username": "root",
  "password": "123456"
}
```

### 2. 数据库结构/数据转换

`POST /api/conversions/database`

```json
{
  "source": {
    "type": "MYSQL",
    "host": "127.0.0.1",
    "port": 3306,
    "databaseName": "demo",
    "username": "root",
    "password": "123456"
  },
  "target": {
    "type": "SQLSERVER",
    "host": "127.0.0.1",
    "port": 1433,
    "databaseName": "demo_target",
    "schemaName": "dbo",
    "username": "sa",
    "password": "Password123"
  },
  "tables": ["user_info", "order_info"],
  "includeData": true,
  "executeOnTarget": false,
  "targetSchema": "dbo"
}
```

### 3. SQL 文件转换

`POST /api/conversions/sql-file`

表单字段：

- `file`: SQL 文件
- `targetType`: `MYSQL` / `SQLSERVER` / `DM`
- `targetSchema`: 转换为 DM 时必填

## 当前边界

当前版本优先解决“可用的项目骨架 + 常见表结构转换”：

- 支持常规表、字段、索引、外键、数据导出为 INSERT
- 适合迁移前的 SQL 生成与预处理
- 更复杂的对象还可以继续扩展：视图、存储过程、触发器、序列、函数、注释、分区、批量导入优化

## 后续建议

如果你要把这个项目继续做成正式生产工具，下一步建议加：

1. 表注释和字段注释迁移
2. 视图 / 存储过程 / 函数转换
3. 大表分页导出和批量插入
4. SQL AST 级解析，而不是纯规则替换
5. 前端改造成 Vue/React 页面
6. 任务记录、转换历史、SQL 文件下载

## 说明

项目名我沿用了当前目录的 `database_convertor` 风格。如果你愿意，我下一步可以继续直接帮你补：

- 登录页和完整后台页面
- 批量导出为 `.sql` 文件
- 一键执行到目标库并返回执行日志
- MySQL / SQL Server / DM 更完整的数据类型映射
