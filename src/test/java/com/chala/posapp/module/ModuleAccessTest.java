package com.chala.posapp.module;

import com.chala.posapp.service.ModuleAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two halves of module gating: which module owns a request, and whether a shop has
 * that module.
 *
 * <p>The behaviour this replaced was three hand-maintained lists that could disagree with each
 * other, so the cases here are mostly the ways they used to disagree.
 */
class ModuleAccessTest {

    private final ModuleRouteResolver resolver = new ModuleRouteResolver();

    /** computeEnabled touches no repository, so the collaborators can be null here. */
    private final ModuleAccessService access = new ModuleAccessService(null, null, null, null);

    @Nested
    @DisplayName("route → module")
    class RouteResolution {

        @Test
        @DisplayName("a child module's route beats its parent's catch-all")
        void childBeatsParent() {
            assertThat(resolver.resolve("/stock-transfers", "GET")).contains("STOCK_TRANSFERS");
            assertThat(resolver.resolve("/stock-adjustments", "POST")).contains("STOCK_ADJUSTMENTS");
            assertThat(resolver.resolve("/stock", "GET")).contains("STOCK");
        }

        @Test
        @DisplayName("a hyphenated sibling is not swallowed by the parent prefix")
        void hyphenatedSiblingIsNotSwallowed() {
            // The old filter used path.startsWith("/stock"), which also matched
            // "/stock-transfers" and "/stock-processing" whether or not that was intended.
            assertThat(resolver.resolve("/stock-processing", "POST")).contains("STOCK_PROCESSING");
        }

        @Test
        @DisplayName("the /api prefix on newer controllers is normalised away")
        void apiPrefixIsNormalised() {
            String path = resolver.normalize("/api/reports/v2/cash-flow");
            assertThat(path).isEqualTo("/reports/v2/cash-flow");
            assertThat(resolver.resolve(path, "GET")).contains("REPORTS_FINANCIAL");
        }

        @Test
        @DisplayName("reads and writes on the same path can belong to different modules")
        void methodScopedRules() {
            // Every client reads the branch list at boot, so only writes are gated.
            assertThat(resolver.isModuleExempt("/branches", "GET")).isTrue();
            assertThat(resolver.resolve("/branches", "POST")).contains("SETTINGS_BRANCHES");

            assertThat(resolver.resolve("/orders", "POST")).contains("POS");
            assertThat(resolver.resolve("/orders/INV-1", "GET")).contains("SALES");
        }

        @Test
        @DisplayName("nested settings routes resolve to their own module, not to branch management")
        void nestedSettingsRoutes() {
            assertThat(resolver.resolve("/branches/3/receipt-settings", "PUT")).contains("SETTINGS_RECEIPT");
            assertThat(resolver.resolve("/branches/3/barcode-label-settings", "PUT")).contains("ITEMS_BARCODE");
        }

        @Test
        @DisplayName("auth, health and the control plane skip both checks")
        void subscriptionExempt() {
            assertThat(resolver.isSubscriptionExempt("/auth/login", "POST")).isTrue();
            assertThat(resolver.isSubscriptionExempt("/health", "GET")).isTrue();
            assertThat(resolver.isSubscriptionExempt(resolver.normalize("/api/saas/my-modules"), "GET")).isTrue();
        }

        @Test
        @DisplayName("boot reads skip the module check but NOT the paywall")
        void bootReadsStillPayWalled() {
            // The regression this guards: exempting the branch list from the module check
            // must not also exempt it from the subscription check, or an expired shop keeps
            // reading its own data.
            for (String path : new String[] { "/branches", "/app-configuration", "/categories" }) {
                assertThat(resolver.isModuleExempt(path, "GET"))
                        .as("%s should skip the module check", path).isTrue();
                assertThat(resolver.isSubscriptionExempt(path, "GET"))
                        .as("%s must still hit the paywall", path).isFalse();
            }
        }

        @Test
        @DisplayName("an unclaimed path is allowed and recorded, not blocked")
        void unmappedPathsAreAllowedAndRecorded() {
            Optional<String> module = resolver.resolve("/something-nobody-declared", "GET");
            assertThat(module).isEmpty();
            assertThat(resolver.unmappedPaths()).contains("GET /something-nobody-declared");
        }
    }

    @Nested
    @DisplayName("plan + override → effective set")
    class Resolution {

        @Test
        @DisplayName("a tenant override wins over the plan default")
        void tenantOverrideWins() {
            Map<String, Boolean> plan = allEnabled();
            plan.put("STOCK_TRANSFERS", false);

            Set<String> withoutOverride = access.computeEnabled(plan, Map.of());
            assertThat(withoutOverride).doesNotContain("STOCK_TRANSFERS");

            Set<String> withOverride = access.computeEnabled(plan, Map.of("STOCK_TRANSFERS", true));
            assertThat(withOverride).contains("STOCK_TRANSFERS");
        }

        @Test
        @DisplayName("switching off a parent takes every child with it")
        void parentCascade() {
            Map<String, Boolean> plan = allEnabled();
            plan.put("STOCK", false);

            Set<String> enabled = access.computeEnabled(plan, Map.of());

            assertThat(enabled).doesNotContain("STOCK", "STOCK_TRANSFERS", "STOCK_ADJUSTMENTS", "STOCK_PROCESSING");
        }

        @Test
        @DisplayName("a child cannot be re-enabled while its parent is off")
        void childCannotEscapeParent() {
            Map<String, Boolean> plan = allEnabled();
            plan.put("REPORTS", false);

            Set<String> enabled = access.computeEnabled(plan, Map.of("REPORTS_SALES", true));

            assertThat(enabled).doesNotContain("REPORTS_SALES");
        }

        @Test
        @DisplayName("locked modules stay on however hard you try to switch them off")
        void lockedModulesCannotBeDisabled() {
            Map<String, Boolean> plan = allEnabled();
            plan.put("ITEMS", false);
            plan.put("SETTINGS", false);

            Set<String> enabled = access.computeEnabled(plan, Map.of("ITEMS", false, "SETTINGS", false));

            assertThat(enabled).contains("ITEMS", "SETTINGS");
        }

        @Test
        @DisplayName("a module the plan has never heard of defaults to on")
        void unknownModuleDefaultsOn() {
            // A module added to the catalog after a plan's rows were written must not vanish
            // for every existing shop at the next deploy.
            Set<String> enabled = access.computeEnabled(Map.of(), Map.of());
            assertThat(enabled).containsAll(ModuleCatalog.keys());
        }
    }

    @Nested
    @DisplayName("seeded presets preserve the behaviour they replaced")
    class Presets {

        @Test
        @DisplayName("FREE cannot ring up a sale, matching the old isFreeOnlyBlock")
        void freeCannotSell() {
            Set<String> enabled = effectiveFor("FREE");
            assertThat(enabled).doesNotContain("POS", "POS_OFFLINE", "POS_DINE_IN");
            assertThat(enabled).doesNotContain("REPORTS", "PURCHASES", "SUPPLIERS", "STOCK", "EXPENSES", "CASH_DROPS");
            assertThat(enabled).contains("DASHBOARD", "SALES", "ITEMS", "CUSTOMERS", "SETTINGS");
        }

        @Test
        @DisplayName("STANDARD sells and holds stock but gets no purchasing or reports")
        void standardTier() {
            Set<String> enabled = effectiveFor("STANDARD");
            assertThat(enabled).contains("POS", "STOCK", "STOCK_ADJUSTMENTS", "EXPENSES", "CASH_DROPS",
                    "SETTINGS_USERS");
            assertThat(enabled).doesNotContain("PURCHASES", "SUPPLIERS", "REPORTS", "STOCK_TRANSFERS",
                    "SHIFTS_HISTORY", "SALES_RETURNS");
        }

        @Test
        @DisplayName("PRO gets the whole catalog")
        void proTier() {
            assertThat(effectiveFor("PRO")).containsAll(ModuleCatalog.keys());
        }

        @Test
        @DisplayName("legacy plan names fold onto their modern tier")
        void legacyNamesFold() {
            assertThat(effectiveFor("MONTHLY_LITE")).isEqualTo(effectiveFor("STANDARD"));
            assertThat(effectiveFor("YEARLY_PRO")).isEqualTo(effectiveFor("PRO"));
            assertThat(effectiveFor("MONTHLY_DEMO")).isEqualTo(effectiveFor("FREE"));
        }

        @Test
        @DisplayName("a custom plan starts fully enabled")
        void customPlanStartsOpen() {
            assertThat(effectiveFor("BAKERY_PRO")).containsAll(ModuleCatalog.keys());
        }

        private Set<String> effectiveFor(String planName) {
            return access.computeEnabled(ModulePresets.forPlanName(planName), Map.of());
        }
    }

    @Test
    @DisplayName("every catalog route pattern resolves back to the module that declared it")
    void catalogIsSelfConsistent() {
        // Guards against a new module quietly shadowing an existing one with a broader pattern.
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            for (ModuleRoute route : definition.routes()) {
                String concrete = route.pattern()
                        .replace("/**", "")
                        .replace("*", "sample");
                if (concrete.isBlank()) {
                    continue;
                }
                String method = route.methods().isEmpty() ? "GET" : route.methods().iterator().next();
                if (resolver.isSubscriptionExempt(concrete, method) || resolver.isModuleExempt(concrete, method)) {
                    continue;
                }
                assertThat(resolver.resolve(concrete, method))
                        .as("%s %s should resolve to %s", method, concrete, definition.key())
                        .contains(definition.key());
            }
        }
    }

    private Map<String, Boolean> allEnabled() {
        Map<String, Boolean> map = new HashMap<>();
        ModuleCatalog.keys().forEach(key -> map.put(key, true));
        return map;
    }
}
