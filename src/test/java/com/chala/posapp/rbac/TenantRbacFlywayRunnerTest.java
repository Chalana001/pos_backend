package com.chala.posapp.rbac;

import com.chala.posapp.config.TenantRbacFlywayRunner;
import com.chala.posapp.config.TenantRbacFlywayRunner.TenantTarget;
import com.chala.posapp.service.TenantRbacBaselineService;
import com.chala.posapp.service.TenantRbacMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantRbacFlywayRunnerTest {

    @Test
    void attemptsEveryMigratedTenantAfterIndividualFailure() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TenantRbacMigrationService service = mock(TenantRbacMigrationService.class);
        TenantRbacBaselineService baseline = mock(TenantRbacBaselineService.class);
        TenantRbacFlywayRunner runner = new TenantRbacFlywayRunner(jdbcTemplate, service, baseline);

        when(jdbcTemplate.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of(
                        new TenantTarget("tenant-a", "tenant_a_db"),
                        new TenantTarget("tenant-b", "tenant_b_db")));
        doThrow(new TenantRbacMigrationService.RbacMigrationException("broken tenant"))
                .when(service).migrateAndVerify("tenant_a_db");

        runner.run(null);

        verify(service).migrateAndVerify("tenant_a_db");
        verify(service).migrateAndVerify("tenant_b_db");
        verify(baseline, org.mockito.Mockito.never()).seedAndBackfill("tenant_a_db");
        verify(baseline).seedAndBackfill("tenant_b_db");
    }

}
