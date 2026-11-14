package com.chala.posapp.rbac;

import com.chala.posapp.entity.TenantDatabase;
import com.chala.posapp.entity.TenantDatabase.MigrationStatus;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantProvisioningService;
import com.chala.posapp.service.TenantRbacBaselineService;
import com.chala.posapp.service.TenantRbacMigrationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantProvisioningCutoverTest {

    private final TenantDatabaseRegistry registry = mock(TenantDatabaseRegistry.class);
    private final TenantRbacMigrationService rbac = mock(TenantRbacMigrationService.class);
    private final TenantRbacBaselineService baseline = mock(TenantRbacBaselineService.class);
    private final TenantProvisioningService provisioning =
            new TenantProvisioningService(null, null, registry, rbac, baseline);

    @Test
    void unknownTenantReturnsNotFoundBeforeRbacMigration() {
        when(registry.findByTenantId("shop-a")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> provisioning.promoteCopiedToMigrated("shop-a"));
        verifyNoInteractions(rbac);
    }

    @Test
    void onlyCopiedTenantCanBePromoted() {
        TenantDatabase migrated = mapping(MigrationStatus.MIGRATED);
        when(registry.findByTenantId("shop-a")).thenReturn(Optional.of(migrated));

        assertThrows(ConflictException.class,
                () -> provisioning.promoteCopiedToMigrated("shop-a"));
        verifyNoInteractions(rbac);
    }

    @Test
    void rbacFailureLeavesPromotionUncalled() {
        when(registry.findByTenantId("shop-a")).thenReturn(Optional.of(mapping(MigrationStatus.COPIED)));
        doThrow(new TenantRbacMigrationService.RbacMigrationException("migration failed"))
                .when(rbac).migrateAndVerify("pos_shop_a");

        assertThrows(ConflictException.class,
                () -> provisioning.promoteCopiedToMigrated("shop-a"));
        verify(registry).findByTenantId("shop-a");
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                .promoteCopiedToMigrated(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void readinessFailureLeavesPromotionUncalled() {
        when(registry.findByTenantId("shop-a")).thenReturn(Optional.of(mapping(MigrationStatus.COPIED)));
        doThrow(new TenantRbacMigrationService.RbacMigrationException("not ready"))
                .when(rbac).requireReady("pos_shop_a");

        assertThrows(ConflictException.class,
                () -> provisioning.promoteCopiedToMigrated("shop-a"));
        verify(registry).findByTenantId("shop-a");
    }

    @Test
    void successfulCutoverRunsRbacBeforeAtomicPromotion() {
        when(registry.findByTenantId("shop-a")).thenReturn(Optional.of(mapping(MigrationStatus.COPIED)));

        assertEquals("pos_shop_a", provisioning.promoteCopiedToMigrated("shop-a"));
        verify(rbac).migrateAndVerify("pos_shop_a");
        verify(rbac).requireReady("pos_shop_a");
        verify(baseline).seedAndBackfill("pos_shop_a");
        verify(registry).promoteCopiedToMigrated("shop-a", "pos_shop_a");
    }

    private TenantDatabase mapping(MigrationStatus status) {
        return TenantDatabase.builder()
                .tenantId("shop-a")
                .dbName("pos_shop_a")
                .status(status)
                .build();
    }
}
