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
public class StockProcessingDefaultsMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-24-stock-processing-defaults-v1";

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

        jdbcTemplate.update("UPDATE items SET stock_processing_enabled = false");
        jdbcTemplate.update("DELETE FROM stock_processing_output_links");
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied stock processing default migration: existing items disabled for processing");
    }
}
