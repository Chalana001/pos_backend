package com.chala.posapp.integration;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only {@code StatementInspector} (wired with
 * {@code hibernate.session_factory.statement_inspector}) that records the SQL
 * statements Hibernate actually sends to the database. It exists so the MySQL
 * bulk-query tests can (a) count the statements one repository call really
 * issues and (b) run {@code EXPLAIN} against the exact generated SQL instead
 * of a hand-written approximation. Never registered in production code.
 */
public class SqlCaptureStatementInspector implements StatementInspector {

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        CAPTURED.add(sql);
        return sql;
    }

    public static void clear() {
        CAPTURED.clear();
    }

    public static List<String> captured() {
        synchronized (CAPTURED) {
            return List.copyOf(CAPTURED);
        }
    }
}
