package com.chala.posapp.module;

import com.chala.posapp.entity.ShopBusinessType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Starting module sets.
 *
 * <p>The three plan presets reproduce, exactly, what {@code SubscriptionFilter}'s hardcoded
 * {@code FREE_BLOCKED_PREFIXES} / {@code STANDARD_BLOCKED_PREFIXES} sets and the frontend
 * {@code PLAN_FEATURES} matrix did before the registry existed — so seeding them changes no
 * shop's behaviour. From here on they are editable from the panel and this class is only the
 * initial value.
 *
 * <p>Anything not listed as disabled is enabled. That direction matters: a module added to the
 * catalog later defaults to on for every plan, which is the safe failure — a shop seeing a new
 * feature is a support conversation, a shop losing one mid-day is an outage.
 */
public final class ModulePresets {

    /**
     * FREE is a look-around demo: the shop can browse the catalogue and see its dashboard but
     * cannot ring up a sale. That is the behaviour {@code isFreeOnlyBlock} shipped with
     * (it blocked {@code POST /orders}) and it is preserved here deliberately.
     */
    private static final Set<String> FREE_DISABLED = Set.of(
            "POS", "POS_OFFLINE", "POS_DINE_IN",
            "SALES_CANCEL", "SALES_RETURNS", "SALES_CREDIT",
            "ITEMS_BULK", "ITEMS_IMPORT", "ITEMS_RECIPE", "ITEMS_BARCODE",
            "ITEMS_WEIGHT", "ITEMS_SERVICE",
            "STOCK", "STOCK_ADJUSTMENTS", "STOCK_TRANSFERS", "STOCK_PROCESSING",
            "PURCHASES", "PURCHASES_GRN", "PURCHASES_RETURNS", "PURCHASES_IMPORT", "PURCHASES_REORDER",
            "SUPPLIERS",
            "EXPENSES", "EXPENSES_TYPES", "CASH_DROPS", "CASH_DROPS_BANK",
            "REPORTS", "REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_FINANCIAL", "REPORTS_FORECAST",
            "REPORTS_CUSTOMER", "REPORTS_SUPPLIER", "REPORTS_RETURNS", "REPORTS_EXCEPTIONS", "REPORTS_EXPORT",
            "SHIFTS_HISTORY",
            "SETTINGS_USERS", "SETTINGS_BRANCHES"
    );

    /** STANDARD sells and holds stock, but does no purchasing and gets no reports. */
    private static final Set<String> STANDARD_DISABLED = Set.of(
            "SALES_CANCEL", "SALES_RETURNS",
            "ITEMS_RECIPE",
            "STOCK_TRANSFERS",
            "PURCHASES", "PURCHASES_GRN", "PURCHASES_RETURNS", "PURCHASES_IMPORT", "PURCHASES_REORDER",
            "SUPPLIERS",
            "REPORTS", "REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_FINANCIAL", "REPORTS_FORECAST",
            "REPORTS_CUSTOMER", "REPORTS_SUPPLIER", "REPORTS_RETURNS", "REPORTS_EXCEPTIONS", "REPORTS_EXPORT",
            "SHIFTS_HISTORY",
            "SETTINGS_BRANCHES"
    );

    /** PRO gets everything in the catalog. */
    private static final Set<String> PRO_DISABLED = Set.of();

    private ModulePresets() {
    }

    /** Whether a plan name has a built-in preset, i.e. is one of the three seeded system plans. */
    public static boolean hasPresetFor(String planName) {
        return switch (planName == null ? "" : planName.toUpperCase(java.util.Locale.ROOT)) {
            case "FREE", "MONTHLY_DEMO", "STANDARD", "MONTHLY_LITE", "YEARLY_LITE", "MONTHLY_BASIC",
                 "PRO", "MONTHLY_PRO", "YEARLY_PRO" -> true;
            default -> false;
        };
    }

    /**
     * The full module map for a plan name: every catalog key mapped to its preset value.
     * Legacy plan names are folded onto their modern equivalent so a shop still sitting on
     * {@code MONTHLY_LITE} resolves the same as {@code STANDARD}.
     */
    public static Map<String, Boolean> forPlanName(String planName) {
        String normalized = planName == null ? "" : planName.toUpperCase(java.util.Locale.ROOT);
        Set<String> disabled = switch (normalized) {
            case "FREE", "MONTHLY_DEMO" -> FREE_DISABLED;
            case "STANDARD", "MONTHLY_LITE", "YEARLY_LITE", "MONTHLY_BASIC" -> STANDARD_DISABLED;
            case "PRO", "MONTHLY_PRO", "YEARLY_PRO" -> PRO_DISABLED;
            // A custom plan created from the panel starts fully enabled; the admin then
            // switches off what that package does not include.
            default -> Set.of();
        };
        return toFullMap(disabled);
    }

    /**
     * Business-type tweaks layered on top of the plan preset when a shop is onboarded.
     * These become {@code tenant_modules} override rows, so the shop shows as "customised"
     * in the panel and the admin can see exactly why.
     */
    public static Map<String, Boolean> forBusinessType(ShopBusinessType businessType) {
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        if (businessType == null) {
            return overrides;
        }
        switch (businessType) {
            case RETAIL -> overrides.put("POS_DINE_IN", false);
            case RESTAURANT -> {
                overrides.put("POS_DINE_IN", true);
                overrides.put("ITEMS_RECIPE", true);
                overrides.put("ITEMS_BARCODE", false);
            }
            case HYBRID -> {
                // Everything the plan allows; no business-type opinion.
            }
        }
        return overrides;
    }

    private static Map<String, Boolean> toFullMap(Set<String> disabled) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            map.put(definition.key(), definition.locked() || !disabled.contains(definition.key()));
        }
        return map;
    }
}
