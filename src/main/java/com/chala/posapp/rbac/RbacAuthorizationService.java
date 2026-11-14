package com.chala.posapp.rbac;

import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.service.TenantRbacMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class RbacAuthorizationService {
    private final TenantRbacJdbcService jdbcService;
    private final TenantRbacMigrationService migrationService;

    public boolean isTenantOwner(Long userId) {
        return jdbcService.isOwner(userId);
    }

    public boolean canManageOperationalRoles(User user) {
        return user != null && (user.getRole() == Role.ADMIN || isTenantOwner(user.getId()));
    }

    public boolean canManageOwnership(User user) {
        return user != null && isTenantOwner(user.getId());
    }

    public void requireOperationalRoleManager(User user) {
        if (!canManageOperationalRoles(user)) {
            throw new TenantOwnershipException("Operational role management denied");
        }
    }

    public void requireOwner(User user) {
        if (!canManageOwnership(user)) {
            throw new TenantOwnershipException("Tenant owner authority required");
        }
    }

    public boolean canUseLegacyCompatibility(User user, LegacyCompatibility compatibility) {
        if (user == null || compatibility == null || compatibility == LegacyCompatibility.NONE) return false;
        if (user.getRole() == Role.SUPER_ADMIN) return false;
        return switch (compatibility) {
            case CASHIER -> user.getRole() == Role.CASHIER;
            case MANAGER -> user.getRole() == Role.MANAGER;
            case ADMIN -> user.getRole() == Role.ADMIN;
            case NONE -> false;
        };
    }

    public boolean isSupportedTenantRequest() {
        String catalog = jdbcService.catalogForCurrentTenant();
        return migrationService.isReady(catalog);
    }

    public void validateGrantKeys(Set<String> permissionKeys) {
        if (permissionKeys == null) return;
        permissionKeys.forEach(key -> {
            PermissionDefinition definition = PermissionCatalog.get(key);
            if (definition.protectedPermission() || !definition.tenantGrantable()) {
                throw new TenantOwnershipException("Permission is code-owned and cannot be granted: " + key);
            }
        });
    }

}
