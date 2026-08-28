package com.chala.posapp.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.chala.posapp.module.ModuleCategory.*;
import static com.chala.posapp.module.ModuleDefinition.child;
import static com.chala.posapp.module.ModuleDefinition.locked;
import static com.chala.posapp.module.ModuleDefinition.top;
import static com.chala.posapp.module.ModuleRoute.any;
import static com.chala.posapp.module.ModuleRoute.of;
import static com.chala.posapp.module.ModuleRoute.writes;

/**
 * The single source of truth for what a shop can be sold.
 *
 * <p>This replaces three hand-maintained lists that used to drift apart:
 * {@code SubscriptionFilter}'s {@code FREE_BLOCKED_PREFIXES} / {@code STANDARD_BLOCKED_PREFIXES},
 * and the frontend {@code PLAN_FEATURES} matrix. Both the API gate and the POS app now read
 * their answer from here, so a module can only be added in one place.
 *
 * <p><strong>Keys are permanent.</strong> {@code plan_modules} and {@code tenant_modules} store
 * them as strings; renaming one silently resets every shop that had an override on it.
 *
 * <p>Ordering in {@link #ALL} drives display order in the panel. Children follow their parent.
 */
public final class ModuleCatalog {

    /**
     * Paths exempt from <strong>both</strong> the subscription check and the module check.
     *
     * <p>Deliberately the same short list the old filter skipped: a blocked or expired shop must
     * still be able to reach the login endpoint and see <em>why</em> it is blocked, and
     * {@code /api/saas} is the control plane deciding that. Nothing that returns shop data
     * belongs here — putting a data route in this list would let a non-paying shop keep reading.
     */
    public static final List<ModuleRoute> SUBSCRIPTION_EXEMPT = List.of(
            any("/auth/**"),
            any("/health"),
            any("/api/saas/**"),
            any("/saas/**")
    );

    /**
     * Paths exempt from the <strong>module</strong> check only — the subscription check still
     * applies, so an expired shop is still stopped at the paywall.
     *
     * <p>These are the reads every client makes before it can render anything: the branch list,
     * the shop configuration and the category tree. Gating them would mean switching off a
     * module could leave the app unable to boot at all rather than merely missing a screen.
     * Writes to the same paths are gated normally — {@code SETTINGS_BRANCHES} owns
     * {@code POST/PUT/PATCH/DELETE /branches}.
     */
    public static final List<ModuleRoute> MODULE_EXEMPT = List.of(
            of("/branches", "GET"),
            of("/branches/**", "GET"),
            of("/app-configuration", "GET"),
            of("/app-configuration/**", "GET"),
            of("/categories", "GET"),
            of("/categories/**", "GET"),
            any("/version/**")
    );

    private static final List<ModuleDefinition> ALL = List.of(

            // ---------------------------------------------------------------- Sales & checkout
            top("DASHBOARD", "Dashboard",
                    "Live sales snapshot, today's totals and quick KPIs on the home screen.",
                    INSIGHTS, "LayoutDashboard",
                    List.of(any("/dashboard/**"), any("/dashboard")),
                    List.of("/dashboard")),

            top("POS", "POS / Checkout",
                    "Ring up sales and take payment. Switching this off leaves the shop read-only.",
                    SALES, "ShoppingCart",
                    List.of(of("/orders", "POST"), of("/orders/**", "POST")),
                    List.of("/pos")),
            child("POS_OFFLINE", "POS", "Offline selling",
                    "Keep selling with no internet and sync the queued sales back when it returns.",
                    SALES, "WifiOff",
                    List.of(any("/orders/offline-import/**"), any("/orders/offline-import")),
                    List.of("/offline-sales")),
            child("POS_DINE_IN", "POS", "Dine-in & tables",
                    "Table map, table-held orders and kitchen tickets. Restaurant shops only.",
                    SALES, "Utensils",
                    List.of(any("/dining-tables/**"), any("/dining-tables"),
                            any("/pending-orders/**"), any("/pending-orders")),
                    List.of("/dining-tables")),

            top("SALES", "Sales history",
                    "Browse and reprint past invoices.",
                    SALES, "ReceiptText",
                    List.of(of("/orders", "GET"), of("/orders/**", "GET")),
                    List.of("/sales")),
            child("SALES_CANCEL", "SALES", "Cancel a sale",
                    "Void a completed invoice and put the stock back.",
                    SALES, "Ban",
                    List.of(any("/orders/*/cancel")),
                    List.of()),
            child("SALES_RETURNS", "SALES", "Sales returns",
                    "Accept goods back against an invoice and issue a credit note.",
                    SALES, "Undo2",
                    List.of(any("/orders/*/returns"), any("/returns/**")),
                    List.of("/sales/:id/return")),
            child("SALES_CREDIT", "SALES", "Credit sales",
                    "Sell on account and track what each customer still owes.",
                    SALES, "HandCoins",
                    List.of(any("/credit/**"), any("/credit")),
                    List.of()),

            top("PROMOTIONS", "Promotions & discounts",
                    "Rule-based discounts, happy-hour pricing and bundle offers.",
                    SALES, "BadgePercent",
                    List.of(any("/promotions/**"), any("/promotions")),
                    List.of("/promotions")),

            top("WARRANTIES", "Warranties",
                    "Issue warranty cards at checkout and look them up later.",
                    SALES, "ShieldCheck",
                    List.of(any("/warranties"), any("/warranties/**")),
                    List.of("/warranties")),
            child("WARRANTIES_CLAIMS", "WARRANTIES", "Warranty claims",
                    "Log and work through claims raised against issued warranties.",
                    SALES, "ClipboardCheck",
                    List.of(any("/warranties/claims/**"), any("/warranties/claims")),
                    List.of("/warranties/claims")),
            child("WARRANTIES_TEMPLATES", "WARRANTIES", "Warranty templates",
                    "Reusable warranty terms the cashier picks from at checkout.",
                    SALES, "FileText",
                    List.of(any("/warranty-templates/**"), any("/warranty-templates")),
                    List.of("/warranties/settings")),

            // ---------------------------------------------------------------- Inventory
            locked("ITEMS", "Items & catalogue",
                    "The product catalogue. Core — a POS cannot run without it.",
                    INVENTORY, "Package",
                    List.of(any("/items"), any("/items/**"), any("/categories"), any("/categories/**")),
                    List.of("/items")),
            child("ITEMS_BULK", "ITEMS", "Bulk add items",
                    "Add many items at once from a grid instead of one form at a time.",
                    INVENTORY, "Rows3",
                    List.of(any("/items/bulk"), any("/items/bulk/**")),
                    List.of("/items/bulk-add")),
            child("ITEMS_IMPORT", "ITEMS", "Excel import",
                    "Load the catalogue from a spreadsheet.",
                    INVENTORY, "FileSpreadsheet",
                    List.of(any("/items/import"), any("/items/import/**"),
                            any("/items/import-excel"), any("/items/import-excel/**")),
                    List.of("/items/import-excel")),
            child("ITEMS_RECIPE", "ITEMS", "Recipe items",
                    "Build an item out of ingredients so selling it draws down raw stock.",
                    INVENTORY, "ChefHat",
                    List.of(any("/items/*/recipe"), any("/items/*/recipe/**"),
                            any("/items/import-recipe-ingredients")),
                    List.of("/items/import-recipe-ingredients")),
            // Weight and service items have no API surface of their own — they are item types
            // inside /items — so these carry no routes and are enforced in the POS UI only.
            // They are still catalog modules because they are things a package does or does not
            // include, and the panel has to be able to sell them.
            child("ITEMS_WEIGHT", "ITEMS", "Weight / scale items",
                    "Items priced by weight, read from a scale barcode. UI-only: no separate API.",
                    INVENTORY, "Scale",
                    List.of(),
                    List.of()),
            child("ITEMS_SERVICE", "ITEMS", "Service items",
                    "Non-stock items such as labour or delivery charges. UI-only: no separate API.",
                    INVENTORY, "Wrench",
                    List.of(),
                    List.of()),
            child("ITEMS_BARCODE", "ITEMS", "Barcode label printing",
                    "Design and print shelf and product barcode labels.",
                    INVENTORY, "Barcode",
                    List.of(any("/items/search-print"), any("/items/search-print/**"),
                            any("/branches/*/barcode-label-settings"),
                            any("/branches/*/barcode-label-settings/**")),
                    List.of("/items/print-barcodes")),

            top("STOCK", "Stock control",
                    "On-hand quantities, stock cards and per-item movement history.",
                    INVENTORY, "Boxes",
                    List.of(any("/stock"), any("/stock/**")),
                    // Both spellings: the POS sidebar links to /stocks, the routes use /stock.
                    List.of("/stock", "/stocks")),
            child("STOCK_ADJUSTMENTS", "STOCK", "Stock adjustments",
                    "Write stock up or down after a count, breakage or wastage.",
                    INVENTORY, "SlidersHorizontal",
                    List.of(any("/stock-adjustments"), any("/stock-adjustments/**")),
                    List.of("/stock/adjustments")),
            child("STOCK_TRANSFERS", "STOCK", "Branch transfers",
                    "Move stock between branches with a dispatch and receive step.",
                    INVENTORY, "ArrowLeftRight",
                    List.of(any("/stock-transfers"), any("/stock-transfers/**")),
                    List.of("/stock/transfers")),
            child("STOCK_PROCESSING", "STOCK", "Stock processing",
                    "Break bulk into sale units, or assemble units into a pack.",
                    INVENTORY, "Combine",
                    List.of(any("/stock-processing"), any("/stock-processing/**")),
                    List.of("/stock/processing")),

            // ---------------------------------------------------------------- Purchasing
            top("PURCHASES", "Purchases",
                    "Record what the shop buys in, with cost prices and payment terms.",
                    PURCHASING, "ShoppingBag",
                    List.of(any("/purchases"), any("/purchases/**")),
                    // Both spellings: the POS sidebar links to /purchase, the routes use /purchases.
                    List.of("/purchases", "/purchase")),
            child("PURCHASES_GRN", "PURCHASES", "Goods received notes",
                    "Receive a delivery against a purchase order line by line.",
                    PURCHASING, "PackageCheck",
                    List.of(any("/grn"), any("/grn/**")),
                    List.of()),
            child("PURCHASES_RETURNS", "PURCHASES", "Purchase returns",
                    "Send goods back to a supplier and raise a debit note.",
                    PURCHASING, "Undo2",
                    List.of(any("/purchases/*/returns"), any("/purchase-returns/**"), any("/purchase-returns")),
                    List.of("/purchases/:id/return")),
            child("PURCHASES_IMPORT", "PURCHASES", "Purchase Excel import",
                    "Load a supplier invoice from a spreadsheet instead of keying it.",
                    PURCHASING, "FileSpreadsheet",
                    List.of(any("/purchases/import"), any("/purchases/import/**"),
                            any("/purchases/import-excel"), any("/purchases/import-excel/**")),
                    List.of("/purchases/import-excel")),
            child("PURCHASES_REORDER", "PURCHASES", "Reorder planning",
                    "Suggested purchase quantities from demand history and lead times.",
                    PURCHASING, "Repeat",
                    List.of(any("/reorder-plans"), any("/reorder-plans/**")),
                    List.of("/reports/procurement-planning")),

            top("SUPPLIERS", "Suppliers",
                    "Supplier directory, contact details and outstanding payables.",
                    RELATIONSHIPS, "Truck",
                    List.of(any("/suppliers"), any("/suppliers/**")),
                    List.of("/suppliers")),

            // ---------------------------------------------------------------- Customers
            top("CUSTOMERS", "Customers",
                    "Customer directory, purchase history and loyalty details.",
                    RELATIONSHIPS, "Users",
                    List.of(any("/customers"), any("/customers/**")),
                    List.of("/customers")),
            child("CUSTOMERS_NOTES", "CUSTOMERS", "Customer notes",
                    "Free-text notes staff can leave on a customer record.",
                    RELATIONSHIPS, "StickyNote",
                    List.of(any("/customer-notes/**"), any("/customer-notes"), any("/customers/*/notes")),
                    List.of()),

            // ---------------------------------------------------------------- Cash & finance
            top("SHIFTS", "Shifts & till",
                    "Open and close a till with a counted cash declaration.",
                    FINANCE, "Clock",
                    List.of(any("/shifts"), any("/shifts/**")),
                    List.of("/shifts")),
            child("SHIFTS_HISTORY", "SHIFTS", "Shift history",
                    "Review closed shifts across all cashiers, with over/short variance.",
                    FINANCE, "History",
                    List.of(any("/shifts/all"), any("/shifts/all/**")),
                    List.of("/shifts/history")),

            top("EXPENSES", "Expenses",
                    "Record day-to-day shop spending against the till.",
                    FINANCE, "Wallet",
                    List.of(any("/expenses"), any("/expenses/**")),
                    List.of("/expenses")),
            child("EXPENSES_TYPES", "EXPENSES", "Expense categories",
                    "Maintain the list of expense categories staff choose from.",
                    FINANCE, "Tags",
                    List.of(any("/expense-types"), any("/expense-types/**")),
                    List.of("/expenses/settings")),

            top("CASH_DROPS", "Cash drops",
                    "Log cash taken out of the till for banking or safekeeping.",
                    FINANCE, "Banknote",
                    List.of(any("/cash-drops"), any("/cash-drops/**")),
                    List.of("/cash-drops")),
            child("CASH_DROPS_BANK", "CASH_DROPS", "Bank accounts",
                    "Name the bank accounts a cash drop can be deposited into.",
                    FINANCE, "Landmark",
                    List.of(any("/bank-accounts"), any("/bank-accounts/**")),
                    List.of("/cash-drops/bank-accounts")),

            // ---------------------------------------------------------------- Reports
            top("REPORTS", "Reports",
                    "The reporting section as a whole. Off here hides every report.",
                    INSIGHTS, "BarChart3",
                    List.of(any("/reports"), any("/reports/**")),
                    List.of("/reports")),
            child("REPORTS_SALES", "REPORTS", "Sales reports",
                    "Sales summary, trend, by-category, top sellers, cashier and branch comparison.",
                    INSIGHTS, "TrendingUp",
                    List.of(any("/reports/sales-summary"), any("/reports/sales"), any("/reports/sales-trend"),
                            any("/reports/sales-by-category"), any("/reports/recent-orders"),
                            any("/reports/top-selling"), any("/reports/product-performance"),
                            any("/reports/owner-command-center"),
                            any("/reports/v2/cashier-performance"), any("/reports/v2/branch-comparison"),
                            any("/reports/v2/promotions"), any("/reports/v2/warranties")),
                    List.of("/reports/sales", "/reports/products", "/reports/performance-comparison")),
            child("REPORTS_INVENTORY", "REPORTS", "Inventory reports",
                    "Valuation, low stock, stock movement, transfers and GRN reports.",
                    INSIGHTS, "Warehouse",
                    List.of(any("/reports/low-stock"),
                            any("/reports/v2/inventory-valuation"), any("/reports/v2/stock-movement"),
                            any("/reports/v2/stock-health"), any("/reports/v2/stock-transfers"),
                            any("/reports/v2/grn")),
                    List.of("/reports/inventory", "/reports/stock-health", "/reports/stock-movement",
                            "/reports/stock-transfers")),
            child("REPORTS_FINANCIAL", "REPORTS", "Financial reports",
                    "Profit & loss, cash flow, profit summary, shift summary and expense reports.",
                    INSIGHTS, "Calculator",
                    List.of(any("/reports/profit-summary"), any("/reports/profit"), any("/reports/credit-due"),
                            any("/reports/v2/profit-loss"), any("/reports/v2/cash-flow"),
                            any("/reports/v2/shift-summary"), any("/reports/v2/expenses"),
                            any("/reports/v2/credit-aging"), any("/reports/v2/supplier-payables-aging")),
                    List.of("/reports/profit-loss", "/reports/cash-flow", "/reports/shifts",
                            "/reports/credit-aging", "/reports/supplier-payables")),
            child("REPORTS_FORECAST", "REPORTS", "Forecasting",
                    "Demand forecast and forecast-accuracy tracking.",
                    INSIGHTS, "LineChart",
                    List.of(any("/reports/v2/demand-forecast"), any("/reports/v2/forecast-accuracy")),
                    List.of("/reports/forecast")),
            child("REPORTS_CUSTOMER", "REPORTS", "Customer reports",
                    "Customer behaviour, top customers and customer performance.",
                    INSIGHTS, "UserSearch",
                    List.of(any("/reports/customer-performance"), any("/reports/top-customers"),
                            any("/reports/v2/customer-behavior")),
                    List.of("/reports/customers", "/reports/customer-behavior")),
            child("REPORTS_SUPPLIER", "REPORTS", "Supplier reports",
                    "Supplier performance and top suppliers.",
                    INSIGHTS, "PackageSearch",
                    List.of(any("/reports/supplier-performance"), any("/reports/top-suppliers")),
                    List.of("/reports/suppliers", "/reports/purchases")),
            child("REPORTS_RETURNS", "REPORTS", "Returns reports",
                    "Returns summary, most-returned items, reasons and trend.",
                    INSIGHTS, "RotateCcw",
                    List.of(any("/reports/returns-summary"), any("/reports/top-returned-items"),
                            any("/reports/return-reasons"), any("/reports/return-trend")),
                    List.of("/reports/returns")),
            child("REPORTS_EXCEPTIONS", "REPORTS", "Exception reports",
                    "Voids, discounts, price overrides and other things worth a second look.",
                    INSIGHTS, "TriangleAlert",
                    List.of(any("/reports/v2/exceptions")),
                    List.of("/reports/exceptions", "/reports/commercial-intelligence")),
            child("REPORTS_EXPORT", "REPORTS", "Scheduled export",
                    "Queue a large report to run in the background, and email it on a schedule.",
                    INSIGHTS, "Download",
                    List.of(any("/reports/export-jobs"), any("/reports/export-jobs/**"),
                            any("/reports/schedules"), any("/reports/schedules/**"),
                            any("/operations/report-exports"), any("/operations/report-exports/**")),
                    List.of()),

            // ---------------------------------------------------------------- Administration
            locked("SETTINGS", "Shop settings",
                    "Shop-wide configuration. Core — the app reads it on every login.",
                    ADMIN, "Settings",
                    List.of(any("/app-configuration"), any("/app-configuration/**")),
                    List.of("/app-configuration")),
            child("SETTINGS_RECEIPT", "SETTINGS", "Receipt designer",
                    "Customise receipt header, footer and printed line layout.",
                    ADMIN, "Printer",
                    List.of(any("/branches/*/receipt-settings"), any("/branches/*/receipt-settings/**")),
                    List.of("/receipt-settings")),
            child("SETTINGS_BRANCHES", "SETTINGS", "Branch management",
                    "Create and edit branches. Reading the branch list always stays on.",
                    ADMIN, "Building2",
                    List.of(writes("/branches"), writes("/branches/**")),
                    List.of("/branches")),
            child("SETTINGS_USERS", "SETTINGS", "Staff accounts",
                    "Create cashier and manager logins and set their roles.",
                    ADMIN, "UserCog",
                    List.of(any("/users"), any("/users/**")),
                    List.of("/users"))
    );

    private static final Map<String, ModuleDefinition> BY_KEY;
    private static final Map<String, List<ModuleDefinition>> CHILDREN;
    private static final List<ModuleDefinition> TOP_LEVEL;

    static {
        Map<String, ModuleDefinition> byKey = new LinkedHashMap<>();
        Map<String, List<ModuleDefinition>> children = new LinkedHashMap<>();
        for (ModuleDefinition definition : ALL) {
            if (byKey.put(definition.key(), definition) != null) {
                throw new IllegalStateException("Duplicate module key in catalog: " + definition.key());
            }
            if (definition.parentKey() != null) {
                children.computeIfAbsent(definition.parentKey(), key -> new java.util.ArrayList<>())
                        .add(definition);
            }
        }
        for (ModuleDefinition definition : ALL) {
            if (definition.parentKey() != null && !byKey.containsKey(definition.parentKey())) {
                throw new IllegalStateException(
                        "Module " + definition.key() + " points at unknown parent " + definition.parentKey());
            }
        }
        BY_KEY = Map.copyOf(byKey);
        CHILDREN = Map.copyOf(children);
        TOP_LEVEL = ALL.stream().filter(ModuleDefinition::isTopLevel).toList();
    }

    private ModuleCatalog() {
    }

    public static List<ModuleDefinition> all() {
        return ALL;
    }

    public static List<ModuleDefinition> topLevel() {
        return TOP_LEVEL;
    }

    public static List<ModuleDefinition> childrenOf(String parentKey) {
        return CHILDREN.getOrDefault(parentKey, List.of());
    }

    public static ModuleDefinition byKey(String key) {
        return BY_KEY.get(key);
    }

    public static boolean exists(String key) {
        return BY_KEY.containsKey(key);
    }

    public static Set<String> keys() {
        return BY_KEY.keySet();
    }

    /** Display order for a key, matching declaration order in {@link #ALL}. */
    public static int displayOrder(String key) {
        for (int index = 0; index < ALL.size(); index++) {
            if (ALL.get(index).key().equals(key)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }
}
