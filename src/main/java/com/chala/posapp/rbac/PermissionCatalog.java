package com.chala.posapp.rbac;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.chala.posapp.rbac.PermissionCategory.*;
import static com.chala.posapp.rbac.RiskClass.*;

/**
 * Code-owned capability catalog for the tenant-local RBAC model.
 *
 * <p>Keys are permanent once persisted in {@code role_permissions} or
 * {@code user_permission_overrides}. This catalog is descriptive during Phase 2;
 * it is intentionally not connected to Spring Security enforcement or role seeding.</p>
 */
public final class PermissionCatalog {
    private static final List<PermissionDefinition> ALL = List.of(
            grant("sales.view", "View sales", "Read completed and pending sales.", SALES, LOW),
            grant("sales.create", "Create sales", "Create a sale through the POS.", SALES, MODERATE),
            grant("sales.cancel", "Cancel sales", "Cancel an existing sale.", SALES, HIGH),
            grant("sales.return", "Process returns", "Process a sale return.", SALES, HIGH),
            grant("sales.discount.apply", "Apply discounts", "Apply ordinary sale discounts.", SALES, MODERATE),
            grant("sales.discount.override", "Override discounts", "Override a configured discount restriction.", SALES, HIGH),

            grant("orders.view", "View orders", "Read orders and order details.", ORDERS, LOW),
            grant("orders.create", "Create orders", "Create an order.", ORDERS, MODERATE),
            grant("orders.cancel", "Cancel orders", "Cancel an order.", ORDERS, HIGH),
            grant("orders.pending.manage", "Manage pending orders", "Create, update, and complete pending orders.", ORDERS, MODERATE),

            grant("products.view", "View products", "Read items and product catalogue data.", PRODUCTS, LOW),
            grant("products.create", "Create products", "Create an item.", PRODUCTS, MODERATE),
            grant("products.edit", "Edit products", "Edit an item.", PRODUCTS, MODERATE),
            grant("products.delete", "Delete products", "Delete or deactivate an item.", PRODUCTS, HIGH),

            grant("inventory.view", "View inventory", "Read stock balances and movements.", INVENTORY, LOW),
            grant("inventory.adjust", "Adjust inventory", "Adjust stock quantities.", INVENTORY, HIGH),
            grant("inventory.transfer", "Transfer inventory", "Dispatch or receive stock transfers.", INVENTORY, HIGH),
            grant("inventory.process", "Process inventory", "Process recipe or stock-processing operations.", INVENTORY, HIGH),

            grant("purchases.view", "View purchases", "Read purchases and goods received notes.", PURCHASES, LOW),
            grant("purchases.create", "Create purchases", "Create a purchase or goods received note.", PURCHASES, MODERATE),
            grant("purchases.edit", "Edit purchases", "Edit an unfinalized purchase.", PURCHASES, MODERATE),
            grant("purchases.return", "Return purchases", "Return purchased stock to a supplier.", PURCHASES, HIGH),

            grant("customers.view", "View customers", "Read customer records.", CUSTOMERS, LOW),
            grant("customers.create", "Create customers", "Create a customer record.", CUSTOMERS, MODERATE),
            grant("customers.edit", "Edit customers", "Edit a customer record.", CUSTOMERS, MODERATE),
            grant("customers.notes.manage", "Manage customer notes", "Create or edit customer notes.", CUSTOMERS, MODERATE),
            grant("customers.orders.view", "View customer orders", "Read order history for a customer.", CUSTOMERS, MODERATE),

            grant("suppliers.view", "View suppliers", "Read supplier records.", SUPPLIERS, LOW),
            grant("suppliers.manage", "Manage suppliers", "Create, edit, or deactivate suppliers.", SUPPLIERS, MODERATE),

            grant("expenses.view", "View expenses", "Read expense records.", EXPENSES, MODERATE),
            grant("expenses.create", "Create expenses", "Create an expense.", EXPENSES, HIGH),
            grant("expenses.edit", "Edit expenses", "Edit or delete an expense.", EXPENSES, HIGH),
            grant("expenses.types.manage", "Manage expense types", "Create or edit expense types.", EXPENSES, HIGH),

            grant("reports.view", "View reports", "View operational reports.", REPORTS, MODERATE),
            grant("reports.profit_loss.view", "View profit and loss", "View profit and loss reporting.", REPORTS, HIGH),
            grant("reports.export.create", "Create report exports", "Request a report export.", REPORTS, HIGH),
            grant("reports.export.manage", "Manage report exports", "View or manage report export jobs owned by the user.", REPORTS, HIGH),
            grant("reports.schedule.manage", "Manage report schedules", "Create, update, and delete scheduled reports.", REPORTS, HIGH),

            grant("users.view", "View users", "Read tenant user records.", USERS, MODERATE),
            grant("users.manage", "Manage users", "Create, edit, enable, or disable tenant users.", USERS, CRITICAL),
            protectedPermission("users.admin.floor", "Administrator protection", "Maintain the code-owned minimum administrator capability floor.", USERS, CRITICAL),
            protectedPermission("tenant.owner.manage", "Manage tenant ownership", "Perform protected tenant ownership administration.", USERS, CRITICAL),
            protectedPermission("tenant.owner.transfer", "Transfer tenant ownership", "Transfer the single active tenant owner.", USERS, CRITICAL),
            protectedPermission("tenant.owner.lifecycle", "Manage tenant lifecycle", "Perform protected owner-only tenant lifecycle operations.", SETTINGS, CRITICAL),
            grant("staff.view", "View staff", "Read staff and attendance-related records.", STAFF, LOW),
            grant("staff.manage", "Manage staff", "Manage staff records and assignments.", STAFF, HIGH),

            grant("shifts.view", "View shifts", "Read cash shifts.", SHIFTS, MODERATE),
            grant("shifts.open", "Open shifts", "Open a cash shift.", SHIFTS, MODERATE),
            grant("shifts.close", "Close shifts", "Close a cash shift.", SHIFTS, HIGH),
            grant("shifts.cash.manage", "Manage shift cash", "Manage cash drops and shift cash operations.", SHIFTS, HIGH),

            grant("promotions.view", "View promotions", "Read promotions and promotion history.", PROMOTIONS, LOW),
            grant("promotions.manage", "Manage promotions", "Create and edit promotions.", PROMOTIONS, HIGH),
            grant("promotions.approve", "Approve promotions", "Approve a promotion requiring approval.", PROMOTIONS, CRITICAL),
            grant("promotions.codes.manage", "Manage promotion codes", "Manage promotion codes.", PROMOTIONS, HIGH),
            grant("promotions.settings.manage", "Manage promotion settings", "Manage promotion configuration.", PROMOTIONS, HIGH),

            grant("settings.view", "View settings", "Read tenant settings.", SETTINGS, MODERATE),
            grant("settings.manage", "Manage settings", "Change tenant settings.", SETTINGS, HIGH),
            grant("branches.view", "View branches", "Read branch records.", BRANCHES, LOW),
            grant("branches.manage", "Manage branches", "Create, edit, or deactivate branches.", BRANCHES, CRITICAL),

            grant("offline.sales.import", "Import offline sales", "Replay queued offline sales to the server.", OFFLINE, HIGH)
    );

    private static final Map<String, PermissionDefinition> BY_KEY = index(ALL);

    private PermissionCatalog() {
    }

    public static List<PermissionDefinition> all() {
        return ALL;
    }

    public static PermissionDefinition get(String key) {
        return Objects.requireNonNull(BY_KEY.get(key), "Unknown permission: " + key);
    }

    public static boolean contains(String key) {
        return BY_KEY.containsKey(key);
    }

    public static List<PermissionDefinition> tenantGrantable() {
        return ALL.stream().filter(PermissionDefinition::tenantGrantable).toList();
    }

    private static PermissionDefinition grant(String key, String label, String description,
                                               PermissionCategory category, RiskClass risk) {
        return new PermissionDefinition(key, label, description, category, risk, true, true, false);
    }

    private static PermissionDefinition protectedPermission(String key, String label, String description,
                                                   PermissionCategory category, RiskClass risk) {
        return new PermissionDefinition(key, label, description, category, risk, false, false, true);
    }

    private static Map<String, PermissionDefinition> index(List<PermissionDefinition> definitions) {
        Map<String, PermissionDefinition> result = new LinkedHashMap<>();
        for (PermissionDefinition definition : definitions) {
            if (result.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalStateException("Duplicate permission key: " + definition.key());
            }
        }
        return Map.copyOf(result);
    }
}
