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
public class StockBatchSourceTypeMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-24-stock-batch-source-type-v1";

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

        jdbcTemplate.update("UPDATE stock_batches SET source_type = 'PURCHASE' WHERE source_type IS NULL");
        jdbcTemplate.update("UPDATE stock_batches SET source_type = 'PROCESSING' WHERE batch_code LIKE 'PROC-%'");
        jdbcTemplate.update("UPDATE stock_batches SET source_type = 'TRANSFER' WHERE batch_code LIKE 'TRF-%'");
        jdbcTemplate.update("UPDATE stock_batches SET source_type = 'OVERRIDE' WHERE batch_code LIKE 'OVERRIDE-%'");
        jdbcTemplate.update("UPDATE stock_batches SET source_type = 'AUTO' WHERE batch_code LIKE 'AUTO-ZERO-%'");
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock batch source type migration");
    }
}
