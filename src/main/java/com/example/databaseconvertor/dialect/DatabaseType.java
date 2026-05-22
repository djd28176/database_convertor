package com.example.databaseconvertor.dialect;

public enum DatabaseType {
    MYSQL(3306, "com.mysql.cj.jdbc.Driver"),
    SQLSERVER(1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    DM(5236, "dm.jdbc.driver.DmDriver");

    private final int defaultPort;
    private final String driverClassName;

    DatabaseType(int defaultPort, String driverClassName) {
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDriverClassName() {
        return driverClassName;
    }
}
