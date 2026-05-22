package com.example.databaseconvertor.service;

import com.example.databaseconvertor.dialect.DatabaseType;
import com.example.databaseconvertor.dto.DbConnectionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class JdbcConnectionService {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectionService.class);

    public Connection openConnection(DbConnectionRequest request) throws SQLException {
        validate(request);

        String driverClassName = request.driverClassName() == null || request.driverClassName().isBlank()
                ? request.type().getDriverClassName()
                : request.driverClassName();
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException ex) {
            if (!(request.type() == DatabaseType.DM && tryLoadDmDriver(driverClassName))) {
                throw new IllegalStateException("JDBC driver not found: " + driverClassName
                        + ". 请把达梦 JDBC jar 放到项目根目录的 drivers/ 目录下，或加入运行时 classpath。", ex);
            }
        }

        String url = buildUrl(request);
        log.info("打开数据库连接: type={}, url={}, username={}, schemaName={}",
                request.type(), maskJdbcUrl(url), request.username(), request.schemaName());
        String username = request.username() == null ? "" : request.username();
        String password = request.password() == null ? "" : request.password();
        return DriverManager.getConnection(url, username, password);
    }

    private void validate(DbConnectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Connection configuration is required.");
        }
        if (request.type() == null) {
            throw new IllegalArgumentException("Database type is required.");
        }
        if (request.type() == DatabaseType.DM
                && (request.schemaName() == null || request.schemaName().isBlank())) {
            throw new IllegalArgumentException("DM 数据库必须填写模式名(schemaName)。");
        }
        if ((request.jdbcUrl() == null || request.jdbcUrl().isBlank())
                && (request.host() == null || request.host().isBlank())) {
            throw new IllegalArgumentException("Host is required when jdbcUrl is not provided.");
        }
    }

    private String buildUrl(DbConnectionRequest request) {
        if (request.jdbcUrl() != null && !request.jdbcUrl().isBlank()) {
            return request.jdbcUrl();
        }

        int port = request.port() == null || request.port() <= 0
                ? request.type().getDefaultPort()
                : request.port();
        String databaseName = request.databaseName() == null ? "" : request.databaseName().trim();

        return switch (request.type()) {
            case MYSQL -> "jdbc:mysql://" + request.host() + ":" + port + "/" + databaseName
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true";
            case SQLSERVER -> "jdbc:sqlserver://" + request.host() + ":" + port
                    + ";databaseName=" + databaseName + ";encrypt=false;trustServerCertificate=true";
            case DM -> {
                if (databaseName.isBlank()) {
                    yield "jdbc:dm://" + request.host() + ":" + port;
                }
                yield "jdbc:dm://" + request.host() + ":" + port + "/" + databaseName;
            }
        };
    }

    private boolean tryLoadDmDriver(String driverClassName) {
        List<File> jars = findDmDriverJars();
        if (jars.isEmpty()) {
            log.warn("未找到达梦 JDBC 驱动 jar: searchedDirs=drivers,libs,DM_JDBC_DIR,DM_JDBC_JAR");
            return false;
        }

        try {
            log.info("尝试从本地目录加载达梦驱动: jarCount={}, driverClassName={}", jars.size(), driverClassName);
            URL[] urls = jars.stream()
                    .map(file -> {
                        try {
                            return file.toURI().toURL();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .toArray(URL[]::new);
            URLClassLoader classLoader = new URLClassLoader(urls, JdbcConnectionService.class.getClassLoader());
            Class<?> driverClass = Class.forName(driverClassName, true, classLoader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
            log.info("达梦驱动加载成功: driverClassName={}", driverClassName);
            return true;
        } catch (Exception ex) {
            throw new IllegalStateException("检测到达梦驱动 jar，但加载失败。请确认 jar 与当前 JDK 版本兼容。", ex);
        }
    }

    private List<File> findDmDriverJars() {
        List<File> jars = new ArrayList<>();
        collectJarFiles(new File("drivers"), jars);
        collectJarFiles(new File("libs"), jars);

        String dmJdbcDir = System.getenv("DM_JDBC_DIR");
        if (dmJdbcDir != null && !dmJdbcDir.isBlank()) {
            collectJarFiles(new File(dmJdbcDir.trim()), jars);
        }

        String dmJdbcJar = System.getenv("DM_JDBC_JAR");
        if (dmJdbcJar != null && !dmJdbcJar.isBlank()) {
            File file = new File(dmJdbcJar.trim());
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jars.add(file);
            }
        }

        List<File> distinct = new ArrayList<>();
        for (File jar : jars) {
            boolean exists = distinct.stream().anyMatch(existing -> Objects.equals(existing.getAbsolutePath(), jar.getAbsolutePath()));
            if (!exists) {
                distinct.add(jar);
            }
        }
        return distinct;
    }

    private void collectJarFiles(File directory, List<File> jars) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles(dmJarFilter());
        if (files == null) {
            return;
        }
        for (File file : files) {
            jars.add(file);
        }
    }

    private FilenameFilter dmJarFilter() {
        return (dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jar") && lower.contains("dm");
        };
    }

    private String maskJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        return jdbcUrl.replaceAll("(?i)(password=)([^;&]+)", "$1******");
    }

    private static final class DriverShim implements Driver {

        private final Driver driver;

        private DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public Connection connect(String url, java.util.Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}
