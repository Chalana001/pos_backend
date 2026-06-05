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
public class PurchaseCashSourceMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-06-05-purchase-cash-source-v2";

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

        addColumnIfMissing("purchase", "cash_source", "ALTER TABLE `purchase` ADD COLUMN cash_source VARCHAR(40) NULL");
        addColumnIfMissing("purchase", "cash_shift_id", "ALTER TABLE `purchase` ADD COLUMN cash_shift_id BIGINT NULL");
        addColumnIfMissing("purchase", "cashier_user_id", "ALTER TABLE `purchase` ADD COLUMN cashier_user_id BIGINT NULL");
        addColumnIfMissing("purchase", "cash_source_amount", "ALTER TABLE `purchase` ADD COLUMN cash_source_amount DECIMAL(12,2) NULL");
        addColumnIfMissing("purchase", "cash_source_branch_id", "ALTER TABLE `purchase` ADD COLUMN cash_source_branch_id BIGINT NULL");

        jdbcTemplate.update("""
                UPDATE `purchase`
                SET cash_source = 'BRANCH_CASH'
                WHERE cash_source IS NULL
                """);
        jdbcTemplate.update("""
                UPDATE `purchase`
                SET cash_source_amount = COALESCE(paid_amount, 0)
                WHERE cash_source_amount IS NULL
                """);

        jdbcTemplate.execute("""
                ALTER TABLE `purchase`
                MODIFY COLUMN cash_source VARCHAR(40) NOT NULL DEFAULT 'BRANCH_CASH'
                """);
        jdbcTemplate.execute("""
                ALTER TABLE `purchase`
                MODIFY COLUMN cash_source_amount DECIMAL(12,2) NOT NULL DEFAULT 0
                """);

        addColumnIfMissing("supplier_payments", "cash_source", "ALTER TABLE supplier_payments ADD COLUMN cash_source VARCHAR(40) NULL");
        addColumnIfMissing("supplier_payments", "cash_shift_id", "ALTER TABLE supplier_payments ADD COLUMN cash_shift_id BIGINT NULL");
        addColumnIfMissing("supplier_payments", "cashier_user_id", "ALTER TABLE supplier_payments ADD COLUMN cashier_user_id BIGINT NULL");
        addColumnIfMissing("supplier_payments", "cash_source_branch_id", "ALTER TABLE supplier_payments ADD COLUMN cash_source_branch_id BIGINT NULL");

        jdbcTemplate.update("""
                UPDATE supplier_payments
                SET cash_source = 'BRANCH_CASH'
                WHERE cash_source IS NULL
                """);

        jdbcTemplate.execute("""
                ALTER TABLE supplier_payments
                MODIFY COLUMN cash_source VARCHAR(40) NOT NULL DEFAULT 'BRANCH_CASH'
                """);

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied purchase cash source migration");
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer columnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = UPPER(?)
                  AND UPPER(COLUMN_NAME) = UPPER(?)
                """, Integer.class, tableName, columnName);

        if (columnExists == null || columnExists == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
