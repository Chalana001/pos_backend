package com.chala.posapp.config;

import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacBaselineService;
import com.chala.posapp.service.TenantRbacMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(2)
@Profile("!test")
@RequiredArgsConstructor
public class TenantRbacFlywayRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TenantRbacMigrationService migrationService;
    private final TenantRbacBaselineService baselineService;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Override
    public void run(ApplicationArguments args) {
        List<TenantTarget> tenants;
        try {
            tenants = jdbcTemplate.query("""
                    SELECT tenant_id, db_name
                    FROM `%s`.tenant_databases
                    WHERE status = 'MIGRATED'
                    """.formatted(masterDb),
                    (rs, rowNum) -> new TenantTarget(rs.getString("tenant_id"), rs.getString("db_name")));
        } catch (DataAccessException ex) {
            log.error("Could not enumerate migrated tenants for RBAC reconciliation: {}", ex.getMessage(), ex);
            return;
        }

        int succeeded = 0;
        int failed = 0;
        for (TenantTarget tenant : tenants) {
            try {
                migrationService.migrateAndVerify(tenant.catalog());
                migrationService.requireReady(tenant.catalog());
                baselineService.seedAndBackfill(tenant.catalog());
                succeeded++;
                log.info("Tenant RBAC reconciliation succeeded. tenantId={}, catalog={}",
                        tenant.tenantId(), tenant.catalog());
            } catch (TenantRbacMigrationService.RbacMigrationException | TenantRbacBaselineService.BaselineException ex) {
                failed++;
                log.error("Tenant RBAC reconciliation failed. tenantId={}, catalog={}, status remains MIGRATED, reason={}",
                        tenant.tenantId(), tenant.catalog(), ex.getMessage(), ex);
            }
        }
        log.info("Tenant RBAC reconciliation complete. attempted={}, succeeded={}, failed={}",
                tenants.size(), succeeded, failed);
    }

    public record TenantTarget(String tenantId, String catalog) {
    }
}
