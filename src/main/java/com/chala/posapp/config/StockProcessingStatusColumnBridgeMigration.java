package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(200)
public class StockProcessingStatusColumnBridgeMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-stock-processing-status-column-bridge-v1";

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_migrations (
                    migration_key VARCHAR(120) PRIMARY KEY,
                    applied_at TIMESTAMP NOT NULL
                )
                """);

        Integer alreadyApplied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_migrations WHERE migration_key = ?",
                Integer.class,
                MIGRATION_KEY
        );
        if (alreadyApplied != null && alreadyApplied > 0) {
            return;
        }

        if (!columnExists("processing_status")) {
            jdbcTemplate.execute("ALTER TABLE stock_processings ADD processing_status VARCHAR(20)");
        }

        if (columnExists("status")) {
            jdbcTemplate.update("""
                    UPDATE stock_processings
                    SET processing_status = status
                    WHERE (processing_status IS NULL OR TRIM(processing_status) = '')
                      AND status IS NOT NULL
                      AND TRIM(status) <> ''
                    """);
        }

        jdbcTemplate.update("""
                UPDATE stock_processings
                SET processing_status = 'COMPLETED'
                WHERE processing_status IS NULL OR TRIM(processing_status) = ''
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock processing status column bridge migration");
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'STOCK_PROCESSINGS'
                  AND UPPER(COLUMN_NAME) = UPPER(?)
                """, Integer.class, columnName);
        return count != null && count > 0;
    }
}
