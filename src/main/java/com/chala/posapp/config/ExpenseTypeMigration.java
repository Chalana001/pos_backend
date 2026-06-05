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
public class ExpenseTypeMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-26-expense-types-v1";

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

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS expense_types (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    name VARCHAR(120) NOT NULL,
                    count_in_profit_report BOOLEAN NOT NULL DEFAULT TRUE,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_tenant_expense_type_name UNIQUE (tenant_id, name)
                )
                """);

        Integer expenseTypeIdColumnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'EXPENSES'
                  AND UPPER(COLUMN_NAME) = 'EXPENSE_TYPE_ID'
                """, Integer.class);
        if (expenseTypeIdColumnExists == null || expenseTypeIdColumnExists == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE expenses
                    ADD COLUMN expense_type_id BIGINT NULL
                    """);
        }

        Integer countInProfitColumnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'EXPENSES'
                  AND UPPER(COLUMN_NAME) = 'COUNT_IN_PROFIT_REPORT'
                """, Integer.class);
        if (countInProfitColumnExists == null || countInProfitColumnExists == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE expenses
                    ADD COLUMN count_in_profit_report BOOLEAN NOT NULL DEFAULT TRUE
                    """);
        }

        jdbcTemplate.update("""
                INSERT INTO expense_types (tenant_id, name, count_in_profit_report, active, created_at)
                SELECT DISTINCT e.tenant_id, e.category, TRUE, TRUE, CURRENT_TIMESTAMP
                FROM expenses e
                WHERE e.category IS NOT NULL
                  AND TRIM(e.category) <> ''
                  AND NOT EXISTS (
                      SELECT 1
                      FROM expense_types et
                      WHERE et.tenant_id = e.tenant_id
                        AND LOWER(et.name) = LOWER(e.category)
                  )
                """);

        jdbcTemplate.update("""
                UPDATE expenses e
                SET e.expense_type_id = (
                    SELECT MIN(et.id)
                    FROM expense_types et
                    WHERE et.tenant_id = e.tenant_id
                      AND LOWER(et.name) = LOWER(e.category)
                )
                WHERE e.expense_type_id IS NULL
                  AND e.category IS NOT NULL
                  AND TRIM(e.category) <> ''
                """);

        jdbcTemplate.update("""
                UPDATE expenses
                SET count_in_profit_report = TRUE
                WHERE count_in_profit_report IS NULL
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied expense type migration");
    }
}
