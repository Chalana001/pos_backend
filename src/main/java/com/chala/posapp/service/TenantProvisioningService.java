package com.chala.posapp.service;

import com.chala.posapp.entity.TenantDatabase;
import com.chala.posapp.entity.TenantDatabase.MigrationStatus;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private static final String TENANT_MIGRATION_LOCATION = "classpath:db/migration/tenant";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TenantDatabaseRegistry registry;
    private final TenantRbacMigrationService rbacMigrationService;
    private final TenantRbacBaselineService rbacBaselineService;

    @Value("${app.tenant.provisioning.enabled:false}")
    private boolean provisioningEnabled;

    public String provisionTenantDatabase(String tenantId) {
        if (!provisioningEnabled) {
            throw new IllegalStateException("Tenant provisioning is disabled. Set app.tenant.provisioning.enabled=true to enable Phase 4.");
        }

        String dbName = TenantDatabaseRegistry.dbNameForTenant(tenantId);
        registry.register(tenantId, dbName, MigrationStatus.PENDING);
        try {
            provisionSchema(dbName);
            rbacMigrationService.migrateAndVerify(dbName);
            rbacMigrationService.requireReady(dbName);
            rbacBaselineService.seedAndBackfill(dbName);
            registry.register(tenantId, dbName, MigrationStatus.MIGRATED);
            log.info("Provisioned tenant database. tenantId={}, dbName={}", tenantId, dbName);
            return dbName;
        } catch (RuntimeException ex) {
            registry.register(tenantId, dbName, MigrationStatus.FAILED);
            throw ex;
        }
    }

    public String promoteCopiedToMigrated(String tenantId) {
        TenantDatabase copied = registry.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (copied.getStatus() != MigrationStatus.COPIED) {
            throw new ConflictException("Tenant must be COPIED before cutover: " + tenantId);
        }
        try {
            rbacMigrationService.migrateAndVerify(copied.getDbName());
            rbacMigrationService.requireReady(copied.getDbName());
            rbacBaselineService.seedAndBackfill(copied.getDbName());
        } catch (TenantRbacMigrationService.RbacMigrationException | TenantRbacBaselineService.BaselineException ex) {
            throw new ConflictException("Tenant RBAC baseline is not ready for cutover: " + tenantId);
        }
        registry.promoteCopiedToMigrated(tenantId, copied.getDbName());
        return copied.getDbName();
    }

    public void provisionSchema(String dbName) {
        String quotedDbName = quoteIdentifier(dbName);
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + quotedDbName
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(dbName)
                .defaultSchema(dbName)
                .locations(TENANT_MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private String quoteIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe database identifier: " + value);
        }
        return "`" + value + "`";
    }
}
