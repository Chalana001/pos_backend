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
public class ReceiptPrinterSettingsMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-06-06-receipt-printer-settings-v1";

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

        addColumnIfMissing("direct_print_enabled", "ALTER TABLE receipt_template_settings ADD direct_print_enabled BOOLEAN DEFAULT FALSE");
        addColumnIfMissing("printer_name", "ALTER TABLE receipt_template_settings ADD printer_name VARCHAR(160)");
        addColumnIfMissing("printer_copies", "ALTER TABLE receipt_template_settings ADD printer_copies INT DEFAULT 1");

        jdbcTemplate.update("""
                UPDATE receipt_template_settings
                SET direct_print_enabled = COALESCE(direct_print_enabled, FALSE),
                    printer_copies = CASE
                        WHEN printer_copies IS NULL OR printer_copies < 1 THEN 1
                        WHEN printer_copies > 10 THEN 10
                        ELSE printer_copies
                    END
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied receipt printer settings migration");
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
