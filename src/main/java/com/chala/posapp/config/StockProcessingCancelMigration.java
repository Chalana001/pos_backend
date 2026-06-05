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
@Order(210)
public class StockProcessingCancelMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-stock-processing-cancel-v1";

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

        addColumnIfMissing("processing_status", "ALTER TABLE stock_processings ADD processing_status VARCHAR(20)");
        addColumnIfMissing("cancel_reason", "ALTER TABLE stock_processings ADD cancel_reason VARCHAR(255)");
        addColumnIfMissing("canceled_by_user_id", "ALTER TABLE stock_processings ADD canceled_by_user_id BIGINT");
        addColumnIfMissing("canceled_at", "ALTER TABLE stock_processings ADD canceled_at TIMESTAMP");

        jdbcTemplate.update("UPDATE stock_processings SET processing_status = 'COMPLETED' WHERE processing_status IS NULL OR TRIM(processing_status) = ''");
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock processing cancel migration");
    }

    private void addColumnIfMissing(String columnName, String ddl) {
        Integer columnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'STOCK_PROCESSINGS'
                  AND UPPER(COLUMN_NAME) = UPPER(?)
                """, Integer.class, columnName);
        if (columnExists == null || columnExists == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
