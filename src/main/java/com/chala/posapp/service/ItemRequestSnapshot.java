package com.chala.posapp.service;

import com.chala.posapp.entity.ItemType;

/**
 * Immutable, request-local snapshot of every configuration and tenant-plan
 * value the item retrieval and mapping paths read per item today
 * ({@code ItemService.searchForPos}, {@code ItemService.searchForPurchase},
 * {@code ItemService.mapToResponse}).
 *
 * <p>Today those paths call {@code AppConfigurationService.isItemTypeEnabled}
 * and {@code isKotEnabled} inside their per-item loops; every call re-reads the
 * configuration row and, for non-NORMAL types, re-queries the master database
 * for the tenant plan. One snapshot is built per request instead, and the
 * values are read from it.
 *
 * <p>The record holds only scalars and nested records — never JPA entities —
 * so reading a property performs no database access and cannot leak a
 * tenant's values into another request.
 *
 * <p><b>Branch scope.</b> {@code branchId} is preserved verbatim from the
 * request and the three scopes are NOT interchangeable:
 * <ul>
 *   <li>concrete id — item stock is scoped to that branch;</li>
 *   <li>{@code 0} — item stock aggregates every branch;</li>
 *   <li>{@code null} — the stock block is skipped entirely.</li>
 * </ul>
 * Configuration resolution follows the existing {@code getOrDefault}
 * semantics: a concrete id looks up the branch row then falls back to the
 * global row, while {@code 0} and {@code null} both normalize to the global
 * row (see {@code AppConfigurationService#resolveConfigurationSnapshot}).
 */
public record ItemRequestSnapshot(
        String tenantId,
        Long branchId,
        Configuration configuration,
        TenantPlan plan) {

    /**
     * The configuration flags item retrieval and mapping read today. Flags are
     * the effective values after the existing branch → global → default
     * fallback, exactly as {@code AppConfigurationService} resolves them.
     */
    public record Configuration(
            boolean weightItemsEnabled,
            boolean servicesEnabled,
            boolean recipeItemsEnabled,
            boolean kotEnabled) {
    }

    /**
     * The tenant plan values behind the item-type eligibility checks, resolved
     * once through the existing master-database lookup. {@code planName} is the
     * resolved scalar the eligibility booleans derive from (kept so later
     * checks and tests can identify the plan); the booleans are the same
     * classification {@code AppConfigurationService.planSupports*} applies.
     */
    public record TenantPlan(
            String planName,
            boolean supportsWeightItems,
            boolean supportsServices,
            boolean supportsRecipeItems) {
    }

    /**
     * Mirrors {@code AppConfigurationService.isItemTypeEnabled(ItemType, Long)}
     * exactly: NORMAL is always enabled, WEIGHT and VOLUME need plan and
     * configuration weight support, SERVICE and RECIPE their own. Compatibility
     * tests pin the mirror to the original.
     */
    public boolean isItemTypeEnabled(ItemType itemType) {
        return switch (itemType) {
            case NORMAL -> true;
            case WEIGHT, VOLUME -> plan.supportsWeightItems() && configuration.weightItemsEnabled();
            case SERVICE -> plan.supportsServices() && configuration.servicesEnabled();
            case RECIPE -> plan.supportsRecipeItems() && configuration.recipeItemsEnabled();
        };
    }

    /** Mirrors {@code AppConfigurationService.isKotEnabled(Long)}. */
    public boolean isKotEnabled() {
        return configuration.kotEnabled();
    }
}
