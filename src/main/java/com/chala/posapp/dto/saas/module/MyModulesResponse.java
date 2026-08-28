package com.chala.posapp.dto.saas.module;

import java.util.List;
import java.util.Map;

/**
 * What the POS app asks for at login to know which menu items and routes to render.
 *
 * <p>This is the replacement for the frontend's hardcoded {@code PLAN_FEATURES} matrix. The old
 * matrix failed <em>open</em> — {@code hasPlanFeature} returned {@code true} for any plan name it
 * did not recognise, so a null or misspelled plan unlocked the whole app. This response is
 * authoritative and enumerated: a key absent from {@code enabled} is off.
 *
 * @param enabled     module keys this shop may use
 * @param routeModule POS frontend route → the module key that owns it, so the router can gate
 *                    without duplicating the mapping
 * @param catalog     every module with its display name, so a screen can say "Reports isn't in
 *                    your package" instead of showing the raw key
 */
public record MyModulesResponse(
        String tenantId,
        String planName,
        List<String> enabled,
        Map<String, String> routeModule,
        List<CatalogEntry> catalog
) {
    /**
     * One module as the POS app needs to describe it to a shop owner.
     *
     * @param enabled whether this shop has it — included so the app can render a
     *                "what you have / what you don't" view without cross-referencing
     */
    public record CatalogEntry(
            String key,
            String parentKey,
            String name,
            String description,
            String category,
            String categoryLabel,
            boolean locked,
            boolean enabled,
            /** Owner-facing sales copy; null for a module with none, so the UI falls back. */
            String headline,
            String pitch,
            List<String> outcomes
    ) {
    }
}
