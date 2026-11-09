package com.chala.posapp.integration;

import com.chala.posapp.entity.AppConfiguration;
import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.repository.AppConfigurationRepository;
import com.chala.posapp.service.AppConfigurationService;
import com.chala.posapp.service.ItemRequestSnapshot;
import com.chala.posapp.service.ItemRequestSnapshotResolver;
import com.chala.posapp.tenant.CurrentTenantResolver;
import com.chala.posapp.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ItemRequestSnapshotResolver} against the real repositories
 * and the real master-database plan lookup: branch → global → default
 * configuration fallback, the three branch scopes, per-tenant plan
 * resolution, request isolation, and — the anchor of the whole exercise —
 * agreement with the existing {@code AppConfigurationService} decisions on
 * identical fixtures. All values asserted here come from actual current
 * behaviour, not from any new rule.
 */
class ItemRequestSnapshotIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private ItemRequestSnapshotResolver resolver;

    @Autowired
    private AppConfigurationService appConfigurationService;

    @Autowired
    private AppConfigurationRepository appConfigurationRepository;

    // ---------------------------------------------------------------
    // A. Branch configuration fallback on real rows
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branch row overrides the global row; missing rows fall back exactly like the legacy service")
    void branchConfigurationFallbackMatchesLegacyService() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("snap-cfg"), 3);

        // Global row and a branch-specific row that disagree on every flag.
        appConfigurationRepository.save(configurationRow(null,
                true, false, true, false));
        appConfigurationRepository.save(configurationRow(fixture.mainBranch().getId(),
                false, true, false, true));

        ItemRequestSnapshot snapshot = TenantContext.callWith(
                fixture.tenantId(), () -> resolver.resolve(fixture.mainBranch().getId()));

        // Branch row wins wholesale.
        assertThat(snapshot.configuration().weightItemsEnabled()).isFalse();
        assertThat(snapshot.configuration().servicesEnabled()).isTrue();
        assertThat(snapshot.configuration().recipeItemsEnabled()).isFalse();
        assertThat(snapshot.configuration().kotEnabled()).isTrue();

        // ...and agrees with the legacy service on every decision.
        assertMatchesLegacyService(snapshot, fixture.mainBranch().getId(), fixture.tenantId());

        // A branch without its own row gets the global row.
        com.chala.posapp.entity.Branch second = new com.chala.posapp.entity.Branch();
        second.setCode(uniqueKey("SNP2"));
        second.setName(uniqueKey("Snap second branch"));
        second.setAddress("Colombo");
        second.setPhone("0770000000");
        second.setActive(true);
        final Long secondBranchId = branchRepository.save(second).getId();
        ItemRequestSnapshot globalBacked = TenantContext.callWith(
                fixture.tenantId(), () -> resolver.resolve(secondBranchId));
        assertThat(globalBacked.configuration().weightItemsEnabled()).isTrue();
        assertThat(globalBacked.configuration().servicesEnabled()).isFalse();
        assertMatchesLegacyService(globalBacked, globalBacked.branchId(), fixture.tenantId());

        // With no rows at all the built-in defaults apply (everything enabled).
        appConfigurationRepository.deleteAll();
        ItemRequestSnapshot defaults = TenantContext.callWith(
                fixture.tenantId(), () -> resolver.resolve(fixture.mainBranch().getId()));
        assertThat(defaults.configuration().weightItemsEnabled()).isTrue();
        assertThat(defaults.configuration().servicesEnabled()).isTrue();
        assertThat(defaults.configuration().recipeItemsEnabled()).isTrue();
        assertThat(defaults.configuration().kotEnabled()).isTrue();
        assertMatchesLegacyService(defaults, fixture.mainBranch().getId(), fixture.tenantId());
    }

    // ---------------------------------------------------------------
    // B. Branch scopes: concrete / 0 / null
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branchId 0 and null resolve the global row; all three scopes stay distinct in the snapshot")
    void branchScopesStayDistinct() throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey("snap-scope"), 3);
        appConfigurationRepository.save(configurationRow(null, true, true, true, true));
        appConfigurationRepository.save(configurationRow(fixture.mainBranch().getId(),
                false, false, false, false));

        Long mainId = fixture.mainBranch().getId();
        ItemRequestSnapshot concrete = TenantContext.callWith(fixture.tenantId(), () -> resolver.resolve(mainId));
        ItemRequestSnapshot zero = TenantContext.callWith(fixture.tenantId(), () -> resolver.resolve(0L));
        ItemRequestSnapshot unscoped = TenantContext.callWith(fixture.tenantId(), () -> resolver.resolve(null));

        // Scope values are preserved verbatim — 0 is not null and not a branch.
        assertThat(concrete.branchId()).isEqualTo(mainId);
        assertThat(zero.branchId()).isZero();
        assertThat(unscoped.branchId()).isNull();

        // Configuration: concrete uses the branch row; 0 and null both
        // normalize to the global row (getOrDefault semantics, unchanged).
        assertThat(concrete.configuration().weightItemsEnabled()).isFalse();
        assertThat(zero.configuration().weightItemsEnabled()).isTrue();
        assertThat(unscoped.configuration().weightItemsEnabled()).isTrue();

        for (ItemRequestSnapshot snapshot : java.util.List.of(concrete, zero, unscoped)) {
            assertMatchesLegacyService(snapshot, snapshot.branchId(), fixture.tenantId());
        }
    }

    // ---------------------------------------------------------------
    // C. Tenant plan through the real master lookup
    // ---------------------------------------------------------------

    @Test
    @DisplayName("each tenant resolves its own plan and restrictions match the legacy checks")
    void tenantPlanResolvesPerTenant() {
        TenantFixture freeTenant = seedTenantShop(uniqueKey("snap-free"), 3);
        TenantFixture standardTenant = seedTenantShop(uniqueKey("snap-std"), 3);
        setTenantPlan(freeTenant, "FREE");
        setTenantPlan(standardTenant, "STANDARD");

        ItemRequestSnapshot freeSnapshot =
                TenantContext.callWith(freeTenant.tenantId(), () -> resolver.resolve(null));
        ItemRequestSnapshot standardSnapshot =
                TenantContext.callWith(standardTenant.tenantId(), () -> resolver.resolve(null));

        assertThat(freeSnapshot.plan().planName()).isEqualTo("FREE");
        assertThat(freeSnapshot.plan().supportsWeightItems()).isFalse();
        assertThat(freeSnapshot.plan().supportsServices()).isFalse();
        assertThat(freeSnapshot.plan().supportsRecipeItems()).isFalse();

        assertThat(standardSnapshot.plan().planName()).isEqualTo("STANDARD");
        assertThat(standardSnapshot.plan().supportsWeightItems()).isTrue();
        assertThat(standardSnapshot.plan().supportsServices()).isTrue();
        assertThat(standardSnapshot.plan().supportsRecipeItems()).isFalse();

        // Item-type decisions match the legacy service for each tenant, and
        // the two tenants' values never leak into each other.
        assertMatchesLegacyService(freeSnapshot, null, freeTenant.tenantId());
        assertMatchesLegacyService(standardSnapshot, null, standardTenant.tenantId());
        assertThat(freeSnapshot.isItemTypeEnabled(ItemType.WEIGHT))
                .isNotEqualTo(standardSnapshot.isItemTypeEnabled(ItemType.WEIGHT));

        // Resolving the first tenant again is unaffected by the second tenant.
        ItemRequestSnapshot freeAgain =
                TenantContext.callWith(freeTenant.tenantId(), () -> resolver.resolve(null));
        assertThat(freeAgain.plan()).isEqualTo(freeSnapshot.plan());
    }

    // ---------------------------------------------------------------
    // D. Request isolation across tenants and branches
    // ---------------------------------------------------------------

    @Test
    @DisplayName("snapshots for different tenants and branches carry only their own values")
    void requestIsolationAcrossTenantsAndBranches() throws Exception {
        TenantFixture first = seedTenantShop(uniqueKey("snap-iso-a"), 3);
        TenantFixture second = seedTenantShop(uniqueKey("snap-iso-b"), 3);
        // findByTenantId returns a single row per tenant: re-point the fixture
        // subscriptions at distinct plans instead of adding rows.
        setTenantPlan(first, "PRO");
        setTenantPlan(second, "FREE");

        appConfigurationRepository.save(configurationRow(first.mainBranch().getId(),
                true, true, true, true));
        appConfigurationRepository.save(configurationRow(second.mainBranch().getId(),
                false, false, false, false));

        ItemRequestSnapshot firstSnapshot = TenantContext.callWith(
                first.tenantId(), () -> resolver.resolve(first.mainBranch().getId()));
        ItemRequestSnapshot secondSnapshot = TenantContext.callWith(
                second.tenantId(), () -> resolver.resolve(second.mainBranch().getId()));

        assertThat(firstSnapshot.tenantId()).isEqualTo(first.tenantId());
        assertThat(firstSnapshot.plan().planName()).isEqualTo("PRO");
        assertThat(firstSnapshot.configuration().weightItemsEnabled()).isTrue();
        assertThat(firstSnapshot.isItemTypeEnabled(ItemType.WEIGHT)).isTrue();

        assertThat(secondSnapshot.tenantId()).isEqualTo(second.tenantId());
        assertThat(secondSnapshot.plan().planName()).isEqualTo("FREE");
        assertThat(secondSnapshot.configuration().weightItemsEnabled()).isFalse();
        assertThat(secondSnapshot.isItemTypeEnabled(ItemType.NORMAL)).isTrue();

        // The first snapshot is a frozen value: the second resolution cannot
        // have altered it, and re-reading it touches nothing.
        assertThat(firstSnapshot.tenantId()).isEqualTo(first.tenantId());
        assertThat(firstSnapshot.plan().planName()).isEqualTo("PRO");
        assertThat(firstSnapshot.configuration().weightItemsEnabled()).isTrue();

        assertMatchesLegacyService(firstSnapshot, first.mainBranch().getId(), first.tenantId());
        assertMatchesLegacyService(secondSnapshot, second.mainBranch().getId(), second.tenantId());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Every configuration decision in the snapshot equals the legacy service's. */
    private void assertMatchesLegacyService(ItemRequestSnapshot snapshot, Long branchId, String tenantId) {
        for (ItemType itemType : ItemType.values()) {
            boolean legacy = TenantContext.callWith(tenantId,
                    () -> appConfigurationService.isItemTypeEnabled(itemType, branchId));
            assertThat(snapshot.isItemTypeEnabled(itemType))
                    .as("item type %s at branch %s", itemType, branchId)
                    .isEqualTo(legacy);
        }
        boolean legacyKot = TenantContext.callWith(tenantId,
                () -> appConfigurationService.isKotEnabled(branchId));
        assertThat(snapshot.isKotEnabled())
                .as("kot flag at branch %s", branchId)
                .isEqualTo(legacyKot);
    }

    private AppConfiguration configurationRow(
            Long branchId, boolean weight, boolean services, boolean recipe, boolean kot) {
        AppConfiguration configuration = AppConfiguration.builder()
                .branchId(branchId)
                .weightItemsEnabled(weight)
                .servicesEnabled(services)
                .recipeItemsEnabled(recipe)
                .tableManagementEnabled(true)
                .dineInEnabled(true)
                .kotEnabled(kot)
                .build();
        return appConfigurationRepository.save(configuration);
    }

    /** A tenant whose plan differs from the TEST_PLAN_* default. */
    private void setTenantPlan(TenantFixture fixture, String planName) {
        SubscriptionPlan plan = ensurePlan(planName, BillingCycle.MONTHLY, 100.0, 100.0, 5);
        TenantContext.callWith(CurrentTenantResolver.MASTER_TENANT, () -> {
            TenantSubscription subscription = tenantSubscriptionRepository
                    .findByTenantId(fixture.tenantId()).orElseThrow();
            subscription.setPlan(plan);
            return tenantSubscriptionRepository.save(subscription);
        });
    }
}
