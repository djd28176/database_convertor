package com.example.databaseconvertor.dialect;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class DatabaseDialectFactory {

    private final Map<DatabaseType, DatabaseDialect> dialects = new EnumMap<>(DatabaseType.class);

    public DatabaseDialectFactory() {
        register(new MySqlDialect());
        register(new SqlServerDialect());
        register(new DmDialect());
    }

    public DatabaseDialect getDialect(DatabaseType databaseType) {
        DatabaseDialect dialect = dialects.get(databaseType);
        if (dialect == null) {
            throw new IllegalArgumentException("Unsupported database type: " + databaseType);
        }
        return dialect;
    }

    private void register(DatabaseDialect dialect) {
        dialects.put(dialect.getType(), dialect);
    }
}
