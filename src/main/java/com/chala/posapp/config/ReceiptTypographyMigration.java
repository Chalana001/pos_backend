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
public class ReceiptTypographyMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-receipt-typography-v1";

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

        addColumnIfMissing("logo_top_spacing", "ALTER TABLE receipt_template_settings ADD logo_top_spacing INT");
        addColumnIfMissing("receipt_font_family", "ALTER TABLE receipt_template_settings ADD receipt_font_family VARCHAR(40)");

        jdbcTemplate.update("""
                UPDATE receipt_template_settings
                SET logo_top_spacing = 4
                WHERE logo_top_spacing IS NULL
                """);

        jdbcTemplate.update("""
                UPDATE receipt_template_settings
                SET receipt_font_family = 'COURIER_NEW'
                WHERE receipt_font_family IS NULL OR TRIM(receipt_font_family) = ''
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied receipt typography migration");
    }

    private void addColumnIfMissing(String columnName, String ddl) {
        Integer columnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'RECEIPT_TEMPLATE_SETTINGS'
                  AND UPPER(COLUMN_NAME) = UPPER(?)
                """, Integer.class, columnName);
        if (columnExists == null || columnExists == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
