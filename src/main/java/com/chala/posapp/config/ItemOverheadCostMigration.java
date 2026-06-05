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
public class ItemOverheadCostMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-26-item-overhead-cost-v1";

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

        Integer modeColumnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'ITEMS'
                  AND UPPER(COLUMN_NAME) = 'OVERHEAD_COST_MODE'
                """, Integer.class);
        if (modeColumnExists == null || modeColumnExists == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE items
                    ADD COLUMN overhead_cost_mode VARCHAR(20) NOT NULL DEFAULT 'NONE'
                    """);
        }

        Integer valueColumnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'ITEMS'
                  AND UPPER(COLUMN_NAME) = 'OVERHEAD_COST_VALUE'
                """, Integer.class);
        if (valueColumnExists == null || valueColumnExists == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE items
                    ADD COLUMN overhead_cost_value DECIMAL(10,2) NOT NULL DEFAULT 0
                    """);
        }

        jdbcTemplate.update("UPDATE items SET overhead_cost_mode = 'NONE' WHERE overhead_cost_mode IS NULL OR TRIM(overhead_cost_mode) = ''");
        jdbcTemplate.update("UPDATE items SET overhead_cost_value = 0 WHERE overhead_cost_value IS NULL");
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied item overhead cost migration");
    }
}
