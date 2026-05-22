package com.example.databaseconvertor.util;

import java.util.ArrayList;
import java.util.List;

public final class SqlStatementUtils {

    private SqlStatementUtils() {
    }

    public static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return statements;
        }

        String normalized = script.replace("\r\n", "\n");
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                addStatement(statements, current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        addStatement(statements, current.toString());
        return statements;
    }

    private static void addStatement(List<String> statements, String value) {
        String trimmed = value.trim();
        if (trimmed.isBlank() || "GO".equalsIgnoreCase(trimmed)) {
            return;
        }
        statements.add(trimmed);
    }
}
