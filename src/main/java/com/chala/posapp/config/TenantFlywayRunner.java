package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Applies any pending Flyway tenant migrations to every already-registered
 * tenant database on application startup. Runs after MasterFlywayRunner (@Order(0)).
 */
@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class TenantFlywayRunner implements ApplicationRunner {

    private static final String TENANT_MIGRATION_LOCATION = "classpath:db/migration/tenant";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Override
    public void run(ApplicationArguments args) {
        // Always migrate the legacy/fallback database first so its schema is ready
        // even when no tenant header is present (e.g. during login for SUPER_ADMIN).
        migrateDatabase(legacyDb);

        List<String> tenantDbs;
        try {
            tenantDbs = jdbcTemplate.queryForList(
                    "SELECT db_name FROM `" + masterDb + "`.tenant_databases WHERE status = 'MIGRATED'",
                    String.class
            );
        } catch (DataAccessException ex) {
            log.warn("Could not read tenant_databases from master schema '{}'. Skipping tenant migrations. Cause: {}",
                    masterDb, ex.getMessage());
            return;
        }

        if (tenantDbs.isEmpty()) {
            log.info("No registered tenant databases found — skipping tenant Flyway migration.");
            return;
        }

        log.info("Running tenant Flyway migrations for {} tenant database(s)...", tenantDbs.size());
        int ok = 0;
        int failed = 0;
        for (String dbName : tenantDbs) {
            boolean success = migrateDatabase(dbName);
            if (success) ok++; else failed++;
        }

        if (failed == 0) {
            log.info("Tenant Flyway migrations complete: {} succeeded.", ok);
        } else {
            log.error("Tenant Flyway migrations: {} succeeded, {} FAILED.", ok, failed);
        }
    }

    private boolean migrateDatabase(String dbName) {
        try {
            if (dbName == null || !dbName.matches("[A-Za-z0-9_]+")) {
                log.error("Refusing to migrate unsafe database identifier: {}", dbName);
                return false;
            }
            var flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(dbName)
                    .defaultSchema(dbName)
                    .locations(TENANT_MIGRATION_LOCATION)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    // Allow stored procedures (BEGIN…END with internal semicolons).
                    // Without this Flyway splits on every ';' inside the procedure body.
                    .mixed(true)
                    .load();

            // Repair first: removes any failed migration markers from a previous bad run.
            // MySQL DDL is non-transactional so a failed migration leaves a 'failed' row
            // in flyway_schema_history. repair() clears it so we can re-attempt cleanly.
            flyway.repair();
            flyway.migrate();

            log.info("Tenant migration OK: {}", dbName);
            return true;
        } catch (Exception ex) {
            log.error("Tenant migration FAILED for database '{}': {}", dbName, ex.getMessage(), ex);
            return false;
        }
    }
}
