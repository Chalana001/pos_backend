package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockProcessingOutputQuantityMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-25-stock-processing-output-default-qty-v1";

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

        Integer columnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'STOCK_PROCESSING_OUTPUT_LINKS'
                  AND UPPER(COLUMN_NAME) = 'DEFAULT_QUANTITY'
                """, Integer.class);

        if (columnExists == null || columnExists == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE stock_processing_output_links
                    ADD COLUMN default_quantity INTEGER NOT NULL DEFAULT 1000
                    """);
        }

        jdbcTemplate.update("UPDATE stock_processing_output_links SET default_quantity = 1000 WHERE default_quantity IS NULL OR default_quantity <= 0");
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock processing output default quantity migration");
    }
}
