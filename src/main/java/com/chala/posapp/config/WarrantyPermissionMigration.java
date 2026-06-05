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
public class WarrantyPermissionMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-28-warranty-role-permissions-v1";

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

        addColumnIfMissing("warranty_enabled", "BOOLEAN DEFAULT TRUE");
        addColumnIfMissing("admin_warranty_allowed", "BOOLEAN DEFAULT TRUE");
        addColumnIfMissing("manager_warranty_allowed", "BOOLEAN DEFAULT TRUE");
        addColumnIfMissing("cashier_warranty_allowed", "BOOLEAN DEFAULT FALSE");

        jdbcTemplate.update("""
                UPDATE app_configurations
                SET warranty_enabled = COALESCE(warranty_enabled, TRUE),
                    admin_warranty_allowed = COALESCE(admin_warranty_allowed, TRUE),
                    manager_warranty_allowed = COALESCE(manager_warranty_allowed, TRUE),
                    cashier_warranty_allowed = COALESCE(cashier_warranty_allowed, FALSE)
                """);
        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied warranty role permission migration");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME) = 'app_configurations'
                  AND LOWER(COLUMN_NAME) = ?
                """,
                Integer.class,
                columnName
        );
        if (columnCount == null || columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE app_configurations ADD COLUMN " + columnName + " " + definition);
        }
    }
}
