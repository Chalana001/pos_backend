package com.chala.posapp.rbac.enforcement;

import com.chala.posapp.rbac.shadow.ShadowClassification;
import com.chala.posapp.rbac.shadow.ShadowEvaluationStatus;
import com.chala.posapp.rbac.shadow.TenantRbacShadowEvaluator;
import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacMigrationService;
import com.chala.posapp.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicPermissionReadinessTest {
    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void legacyCatalogIsCompatibilityOnly() {
        TenantContext.setTenant("legacy-tenant");
        TenantDatabaseRegistry registry = mock(TenantDatabaseRegistry.class);
        when(registry.resolveCatalog("legacy-tenant")).thenReturn("pos_db");
        TenantRbacShadowEvaluator evaluator = new TenantRbacShadowEvaluator(mock(javax.sql.DataSource.class), registry,
                mock(TenantRbacMigrationService.class), "pos_db", "pos_master", true);

        var result = evaluator.evaluateForEnforcement("orders.create");

        assertEquals(ShadowClassification.LEGACY_ONLY, result.classification());
    }

    @Test
    void migratedCatalogThatIsNotReadyIsCapabilityFailure() {
        TenantContext.setTenant("tenant-a");
        TenantDatabaseRegistry registry = mock(TenantDatabaseRegistry.class);
        TenantRbacMigrationService migration = mock(TenantRbacMigrationService.class);
        when(registry.resolveCatalog("tenant-a")).thenReturn("tenant_a_db");
        when(migration.isReady("tenant_a_db")).thenReturn(false);
        TenantRbacShadowEvaluator evaluator = new TenantRbacShadowEvaluator(mock(javax.sql.DataSource.class), registry,
                migration, "pos_db", "pos_master", true);

        var result = evaluator.evaluateForEnforcement("orders.create");

        assertEquals(ShadowClassification.MAPPED_CAPABILITY, result.classification());
        assertEquals(ShadowEvaluationStatus.NOT_READY, result.status());
    }
}
