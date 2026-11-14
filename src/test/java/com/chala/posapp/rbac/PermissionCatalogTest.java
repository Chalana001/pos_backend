package com.chala.posapp.rbac;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionCatalogTest {

    @Test
    void catalogHasStableUniqueKeysAndCompleteMetadata() {
        List<PermissionDefinition> definitions = PermissionCatalog.all();

        assertThat(definitions).isNotEmpty();
        assertThat(definitions).allSatisfy(permission -> {
            assertThat(permission.key()).matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
            assertThat(permission.label()).isNotBlank();
            assertThat(permission.description()).isNotBlank();
            assertThat(permission.category()).isNotNull();
            assertThat(permission.risk()).isNotNull();
        });
        assertThat(definitions.stream().map(PermissionDefinition::key).toList())
                .hasSameSizeAs(new HashSet<>(definitions.stream().map(PermissionDefinition::key).toList()));
    }

    @Test
    void protectedPermissionsCannotBeTenantGrantedOrIndividuallyAllowed() {
        assertThat(PermissionCatalog.all())
                .filteredOn(PermissionDefinition::protectedPermission)
                .isNotEmpty()
                .allSatisfy(permission -> {
                    assertThat(permission.tenantGrantable()).isFalse();
                    assertThat(permission.individualAllowOverride()).isFalse();
                });
    }

    @Test
    void catalogCollectionsAreImmutable() {
        assertThatThrownBy(() -> PermissionCatalog.all().add(PermissionCatalog.get("sales.view")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> PermissionCatalog.tenantGrantable().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lookupUsesTheStableKey() {
        PermissionDefinition permission = PermissionCatalog.get("orders.cancel");

        assertThat(permission.key()).isEqualTo("orders.cancel");
        assertThat(PermissionCatalog.contains("orders.cancel")).isTrue();
        assertThat(PermissionCatalog.contains("orders.cancelled")).isFalse();
        assertThatThrownBy(() -> PermissionCatalog.get("orders.cancelled"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void permissionDefinitionRejectsProtectedGrantableMetadata() {
        assertThatThrownBy(() -> new PermissionDefinition(
                "security.breakglass",
                "Break glass",
                "Must never be tenant granted.",
                PermissionCategory.SETTINGS,
                RiskClass.CRITICAL,
                true,
                false,
                true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
