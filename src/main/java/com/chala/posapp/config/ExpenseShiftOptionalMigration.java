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
public class ExpenseShiftOptionalMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-expense-shift-optional-v1";

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

        Integer shiftIdIsNullable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND UPPER(TABLE_NAME) = 'EXPENSES'
                  AND UPPER(COLUMN_NAME) = 'SHIFT_ID'
                  AND IS_NULLABLE = 'YES'
                """, Integer.class);

        if (shiftIdIsNullable == null || shiftIdIsNullable == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE expenses
                    MODIFY COLUMN shift_id BIGINT NULL
                    """);
        }

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied expense shift optional migration");
    }
}
