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
@Order(220)
public class StockProcessingStatusNormalizationMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-stock-processing-status-normalization-v1";

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

        int fixedRows = jdbcTemplate.update("""
                UPDATE stock_processings
                SET processing_status = 'COMPLETED',
                    cancel_reason = NULL,
                    canceled_by_user_id = NULL,
                    canceled_at = NULL
                WHERE processing_status = 'CANCELED'
                  AND cancel_reason IS NULL
                  AND canceled_by_user_id IS NULL
                  AND canceled_at IS NULL
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock processing status normalization migration. Fixed {} row(s)", fixedRows);
    }
}
