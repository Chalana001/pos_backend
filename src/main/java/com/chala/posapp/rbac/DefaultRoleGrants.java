package com.chala.posapp.rbac;

import com.chala.posapp.entity.Role;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Code-owned compatibility grants for the legacy tenant roles.
 *
 * <p>These rows prepare the future dynamic RBAC model only. Spring Security continues to use
 * {@link Role} and the legacy users.role column in this phase.</p>
 */
public final class DefaultRoleGrants {
    private static final Map<Role, Set<String>> GRANTS = build();

    private DefaultRoleGrants() {
    }

    public static Set<String> forRole(Role role) {
        if (role == null || role == Role.SUPER_ADMIN) {
            return Set.of();
        }
        return GRANTS.getOrDefault(role, Set.of());
    }

    public static Map<Role, Set<String>> all() {
        return GRANTS;
    }

    private static Map<Role, Set<String>> build() {
        EnumMap<Role, Set<String>> result = new EnumMap<>(Role.class);

        result.put(Role.ADMIN, immutable(
                "sales.view", "sales.create", "sales.cancel", "sales.return",
                "sales.discount.apply", "sales.discount.override",
                "orders.view", "orders.create", "orders.cancel", "orders.pending.manage",
                "products.view", "products.create", "products.edit", "products.delete",
                "inventory.view", "inventory.adjust", "inventory.transfer", "inventory.process",
                "purchases.view", "purchases.create", "purchases.edit", "purchases.return",
                "customers.view", "customers.create", "customers.edit", "customers.notes.manage",
                "customers.orders.view", "suppliers.view", "suppliers.manage",
                "expenses.view", "expenses.create", "expenses.edit", "expenses.types.manage",
                "reports.view", "reports.profit_loss.view", "reports.export.create",
                "reports.export.manage", "reports.schedule.manage",
                "users.view", "users.manage", "staff.view", "staff.manage",
                "shifts.view", "shifts.open", "shifts.close", "shifts.cash.manage",
                "promotions.view", "promotions.manage", "promotions.approve",
                "promotions.codes.manage", "promotions.settings.manage",
                "settings.view", "settings.manage", "branches.view", "branches.manage",
                "offline.sales.import"));

        result.put(Role.MANAGER, immutable(
                "sales.view", "sales.create", "sales.cancel", "sales.return",
                "sales.discount.apply", "sales.discount.override",
                "orders.view", "orders.create", "orders.cancel", "orders.pending.manage",
                "products.view", "products.create", "products.edit", "products.delete",
                "inventory.view", "inventory.adjust", "inventory.transfer", "inventory.process",
                "purchases.view", "purchases.create", "purchases.edit", "purchases.return",
                "customers.view", "customers.create", "customers.edit", "customers.notes.manage",
                "customers.orders.view", "suppliers.view", "suppliers.manage",
                "expenses.view", "expenses.create", "expenses.edit", "expenses.types.manage",
                "reports.view", "reports.profit_loss.view", "reports.export.create",
                "reports.export.manage", "reports.schedule.manage",
                "staff.view", "staff.manage", "shifts.view", "shifts.open", "shifts.close",
                "shifts.cash.manage", "promotions.view", "promotions.manage",
                "promotions.codes.manage", "settings.view", "settings.manage",
                "branches.view", "offline.sales.import"));

        // Preserve verified legacy cashier behavior, including cancellation and customer-note CRUD.
        result.put(Role.CASHIER, immutable(
                "sales.view", "sales.create", "sales.cancel", "sales.discount.apply",
                "orders.view", "orders.create", "orders.cancel",
                "products.view", "inventory.view", "customers.view", "customers.create",
                "customers.notes.manage", "customers.orders.view", "expenses.view",
                "reports.view", "shifts.view", "shifts.open", "shifts.cash.manage",
                "settings.view", "offline.sales.import"));

        for (Map.Entry<Role, Set<String>> entry : result.entrySet()) {
            for (String key : entry.getValue()) {
                PermissionDefinition permission = PermissionCatalog.get(key);
                if (!permission.tenantGrantable() || permission.protectedPermission()) {
                    throw new IllegalStateException("Default role grant is not tenant-grantable: " + key);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> immutable(String... keys) {
        return Set.copyOf(new LinkedHashSet<>(List.of(keys)));
    }
}
