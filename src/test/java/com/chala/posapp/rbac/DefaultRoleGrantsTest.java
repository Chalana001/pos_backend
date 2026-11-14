package com.chala.posapp.rbac;

import com.chala.posapp.entity.Role;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRoleGrantsTest {
    @Test
    void everyDefaultGrantIsCatalogedTenantGrantableAndUnique() {
        DefaultRoleGrants.all().forEach((role, grants) -> {
            assertThat(role).isIn(Role.ADMIN, Role.MANAGER, Role.CASHIER);
            assertThat(grants).hasSameSizeAs(new HashSet<>(grants));
            assertThat(grants).allSatisfy(key -> {
                PermissionDefinition permission = PermissionCatalog.get(key);
                assertThat(permission.tenantGrantable()).isTrue();
                assertThat(permission.protectedPermission()).isFalse();
            });
        });
    }

    @Test
    void grantCollectionsAndRoleMapAreImmutable() {
        assertThatThrownBy(() -> DefaultRoleGrants.forRole(Role.CASHIER).add("orders.view"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> DefaultRoleGrants.all().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void superAdminHasNoOrdinaryTenantDefaultRoleGrants() {
        assertThat(DefaultRoleGrants.forRole(Role.SUPER_ADMIN)).isEmpty();
    }

    @Test
    void cashierPreservesVerifiedLegacyCapabilities() {
        assertThat(DefaultRoleGrants.forRole(Role.CASHIER)).contains(
                "orders.create",
                "orders.cancel",
                "customers.view",
                "customers.create",
                "customers.notes.manage",
                "customers.orders.view",
                "offline.sales.import",
                "shifts.view",
                "shifts.open",
                "shifts.cash.manage");
    }

    @Test
    void protectedAdministratorFloorNeverEntersDefaultGrants() {
        assertThat(DefaultRoleGrants.all().values())
                .allSatisfy(grants -> assertThat(grants).doesNotContain("users.admin.floor"));
    }
}
