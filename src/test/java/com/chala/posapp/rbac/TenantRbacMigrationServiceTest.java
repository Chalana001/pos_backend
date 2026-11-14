package com.chala.posapp.rbac;

import com.chala.posapp.service.TenantRbacMigrationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantRbacMigrationServiceTest {

    private final TenantRbacMigrationService service =
            new TenantRbacMigrationService(null, "pos_db", "pos_master");

    @Test
    void acceptsSafeIsolatedCatalogName() {
        assertDoesNotThrow(() -> service.validateCatalog("tenant_a_db"));
        assertDoesNotThrow(() -> service.validateCatalog("pos_shop_42"));
    }

    @Test
    void rejectsReservedAndUnsafeCatalogNames() {
        for (String catalog : new String[]{
                null, "", "pos_db", "POS_MASTER", "tenant-a-db", "tenant db", "tenant_db;DROP TABLE users"
        }) {
            assertThrows(TenantRbacMigrationService.RbacMigrationException.class,
                    () -> service.validateCatalog(catalog));
        }
    }
}
