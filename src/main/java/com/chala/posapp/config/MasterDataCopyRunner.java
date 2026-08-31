package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class MasterDataCopyRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Override
    public void run(ApplicationArguments args) {
        String legacy = quoteIdentifier(legacyDb);
        String master = quoteIdentifier(masterDb);

        int plans = 0, subscriptions = 0, billingRecords = 0, masterUsers = 0;

        if (tableExists(legacyDb, "subscription_plans")) {
            plans = jdbcTemplate.update("""
                    INSERT IGNORE INTO %s.subscription_plans
                        (id, name, billing_cycle, initial_price, renewal_price, max_branches)
                    SELECT id, name, billing_cycle, initial_price, renewal_price, max_branches
                    FROM %s.subscription_plans
                    """.formatted(master, legacy));
        } else {
            log.info("Skipping subscription_plans copy — table not found in {}", legacyDb);
        }

        if (tableExists(legacyDb, "tenant_subscriptions")) {
            subscriptions = jdbcTemplate.update("""
                    INSERT IGNORE INTO %s.tenant_subscriptions
                        (id, tenant_id, shop_name, admin_username, plan_id, is_active, blocked,
                         extra_branches, valid_until, notes, created_at, updated_at)
                    SELECT id, tenant_id, shop_name, admin_username, plan_id, is_active, blocked,
                           extra_branches, valid_until, notes, created_at, updated_at
                    FROM %s.tenant_subscriptions
                    """.formatted(master, legacy));
        } else {
            log.info("Skipping tenant_subscriptions copy — table not found in {}", legacyDb);
        }

        if (tableExists(legacyDb, "billing_records")) {
            billingRecords = jdbcTemplate.update("""
                    INSERT IGNORE INTO %s.billing_records
                        (id, tenant_id, action_type, amount, shop_name, reference_note, performed_by, created_at)
                    SELECT id, tenant_id, action_type, amount, shop_name, reference_note, performed_by, created_at
                    FROM %s.billing_records
                    """.formatted(master, legacy));
        } else {
            log.info("Skipping billing_records copy — table not found in {}", legacyDb);
        }

        // Copy SUPER_ADMIN users from legacy DB to master — but only when the legacy
        // users table still has the old single-DB tenant_id column. In the new
        // per-catalog multi-DB schema, pos_db.users has no tenant_id column, so
        // there are no master-level users to copy (they already live in pos_master).
        if (tableExists(legacyDb, "users") && columnExists(legacyDb, "users", "tenant_id")) {
            masterUsers = jdbcTemplate.update("""
                    INSERT IGNORE INTO %s.users
                        (id, tenant_id, username, password_hash, role, enabled, branch_id,
                         offline_pin_hash, created_at, offline_pin_updated_at)
                    SELECT id, tenant_id, username, password_hash, role, enabled, branch_id,
                           offline_pin_hash, created_at, offline_pin_updated_at
                    FROM %s.users
                    WHERE tenant_id = 'MASTER'
                    """.formatted(master, legacy));
        } else {
            log.info("Skipping MASTER users copy — legacy users table has no tenant_id column (already on multi-DB schema)");
        }

        log.info("Master data copy complete. inserted plans={}, subscriptions={}, billingRecords={}, masterUsers={}",
                plans, subscriptions, billingRecords, masterUsers);
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, schema, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String schema, String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                Integer.class, schema, table, column);
        return count != null && count > 0;
    }

    private String quoteIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Unsafe database identifier: " + value);
        }
        return "`" + value + "`";
    }
}
