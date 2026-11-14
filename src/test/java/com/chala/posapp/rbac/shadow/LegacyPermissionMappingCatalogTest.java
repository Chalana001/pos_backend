package com.chala.posapp.rbac.shadow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPermissionMappingCatalogTest {
    @Test
    void mappingsUseExactControllerAndServiceIdentities() {
        assertFalse(LegacyPermissionMappingCatalog.all().isEmpty());
        assertTrue(LegacyPermissionMappingCatalog.all().stream()
                .anyMatch(mapping -> mapping.method().declaringClass().endsWith("OrderController")
                        && mapping.method().methodName().equals("cancel")));
        assertTrue(LegacyPermissionMappingCatalog.all().stream()
                .anyMatch(mapping -> mapping.method().declaringClass().endsWith("OrderService")
                        && mapping.method().methodName().equals("cancelOrder")));
    }

    @Test
    void customerNotesAndHistoryAreAuthenticatedOnlyNotMethodSecurityAllows() {
        assertTrue(LegacyPermissionMappingCatalog.all().stream()
                .filter(mapping -> mapping.method().declaringClass().contains("CustomerNotesController")
                        || mapping.method().declaringClass().contains("CustomerOrdersController"))
                .allMatch(mapping -> mapping.classification() == ShadowClassification.LEGACY_UNCHECKED_AUTHENTICATED
                        && !mapping.selectedForGrantedEvents()));
    }

    @Test
    void allOrdinaryMappingsAreCatalogTenantGrantableAndNoIdentityDuplicatesExist() {
        assertEquals(LegacyPermissionMappingCatalog.all().size(),
                LegacyPermissionMappingCatalog.all().stream().map(LegacyPermissionMapping::method).distinct().count());
        assertTrue(LegacyPermissionMappingCatalog.all().stream()
                .filter(mapping -> mapping.classification() == ShadowClassification.MAPPED_CAPABILITY)
                .allMatch(mapping -> PermissionCatalogTestSupport.tenantGrantable(mapping.permissionKey())));
    }

    static final class PermissionCatalogTestSupport {
        static boolean tenantGrantable(String key) {
            var definition = com.chala.posapp.rbac.PermissionCatalog.get(key);
            return definition.tenantGrantable() && !definition.protectedPermission();
        }
    }
}
