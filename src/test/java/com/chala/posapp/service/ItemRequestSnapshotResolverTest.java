package com.chala.posapp.service;

import com.chala.posapp.entity.AppConfiguration;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.repository.AppConfigurationRepository;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the request-local configuration/plan snapshot: bounded lookups per
 * snapshot creation, the exact branch → global → default fallback of
 * {@code AppConfigurationService.getOrDefault}, verbatim branch-scope
 * handling (concrete id / 0 / null are not interchangeable), per-tenant plan
 * resolution through the existing master-database lookup, and immutability.
 *
 * <p>The real {@code AppConfigurationService} runs over mocked repositories so
 * the very same private resolution and plan classification the old per-item
 * calls use are exercised — the snapshot may not invent new semantics.
 */
class ItemRequestSnapshotResolverTest {

    private AppConfigurationRepository appConfigurationRepository;
    private TenantSubscriptionRepository tenantSubscriptionRepository;
    private AppConfigurationService appConfigurationService;
    private ItemRequestSnapshotResolver resolver;

    @BeforeEach
    void setUp() {
        appConfigurationRepository = mock(AppConfigurationRepository.class);
        tenantSubscriptionRepository = mock(TenantSubscriptionRepository.class);
        appConfigurationService = new AppConfigurationService(
                appConfigurationRepository,
                tenantSubscriptionRepository,
                mock(BranchRepository.class),
                mock(AuthService.class),
                mock(PlatformTransactionManager.class));
        resolver = new ItemRequestSnapshotResolver(appConfigurationService);
        // Plan lookups only run for a real tenant; tests set it explicitly.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TenantSubscription subscriptionWithPlan(String planName) {
        return TenantSubscription.builder()
                .plan(SubscriptionPlan.builder().name(planName).build())
                .build();
    }

    private static AppConfiguration configuration(
            boolean weight, boolean services, boolean recipe, boolean kot) {
        return AppConfiguration.builder()
                .weightItemsEnabled(weight)
                .servicesEnabled(services)
                .recipeItemsEnabled(recipe)
                .kotEnabled(kot)
                .build();
    }

    // ---------------------------------------------------------------
    // F. Lookup counts
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one snapshot creation performs one branch lookup and one plan lookup, and nothing more on re-reads")
    void lookupsAreBoundedPerSnapshot() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchId(5L))
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        ItemRequestSnapshot snapshot = resolver.resolve(5L);

        verify(appConfigurationRepository, times(1)).findByBranchId(5L);
        verify(appConfigurationRepository, never()).findByBranchIdIsNull();
        verify(tenantSubscriptionRepository, times(1)).findByTenantId("tenant-a");

        // Re-reading snapshot values must not touch any repository again.
        for (int i = 0; i < 3; i++) {
            assertThat(snapshot.configuration().weightItemsEnabled()).isTrue();
            assertThat(snapshot.configuration().kotEnabled()).isTrue();
            assertThat(snapshot.plan().planName()).isEqualTo("PRO");
            assertThat(snapshot.isItemTypeEnabled(ItemType.WEIGHT)).isTrue();
            assertThat(snapshot.isKotEnabled()).isTrue();
        }
        verifyNoMoreInteractions(appConfigurationRepository, tenantSubscriptionRepository);
    }

    @Test
    @DisplayName("the global fallback costs one extra lookup, still bounded per snapshot")
    void globalFallbackLookupIsBounded() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchId(7L)).thenReturn(Optional.empty());
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        resolver.resolve(7L);

        verify(appConfigurationRepository, times(1)).findByBranchId(7L);
        verify(appConfigurationRepository, times(1)).findByBranchIdIsNull();
        verifyNoMoreInteractions(appConfigurationRepository);
    }

    @Test
    @DisplayName("no tenant means no plan lookup at all, matching currentPlanName")
    void masterOrMissingTenantSkipsPlanLookup() {
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, true, true, true)));

        ItemRequestSnapshot withoutTenant = resolver.resolve(null);
        TenantContext.setTenant("MASTER");
        ItemRequestSnapshot masterTenant = resolver.resolve(null);

        verify(tenantSubscriptionRepository, never()).findByTenantId(any());
        // The existing lookup classifies the empty plan name as legacy/unknown:
        // every item type supported.
        assertThat(withoutTenant.plan().planName()).isEmpty();
        assertThat(masterTenant.plan().planName()).isEmpty();
        assertThat(masterTenant.plan().supportsWeightItems()).isTrue();
        assertThat(masterTenant.plan().supportsRecipeItems()).isTrue();
    }

    // ---------------------------------------------------------------
    // A. Branch / global fallback semantics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branch-specific configuration overrides the global row exactly like getOrDefault")
    void branchConfigurationOverridesGlobal() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchId(5L))
                .thenReturn(Optional.of(configuration(false, true, false, false)));
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, false, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        ItemRequestSnapshot snapshot = resolver.resolve(5L);

        // Branch row wins wholesale — no field-by-field merge with the global row.
        assertThat(snapshot.configuration().weightItemsEnabled()).isFalse();
        assertThat(snapshot.configuration().servicesEnabled()).isTrue();
        assertThat(snapshot.configuration().recipeItemsEnabled()).isFalse();
        assertThat(snapshot.configuration().kotEnabled()).isFalse();
        verify(appConfigurationRepository, never()).findByBranchIdIsNull();
    }

    @Test
    @DisplayName("a missing branch row falls back to the global row; a missing global row falls back to defaults")
    void fallbackChainMatchesGetOrDefault() {
        TenantContext.setTenant("tenant-a");
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        // Missing branch row → the global row.
        when(appConfigurationRepository.findByBranchId(5L)).thenReturn(Optional.empty());
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(false, false, false, false)));
        ItemRequestSnapshot globalBacked = resolver.resolve(5L);
        assertThat(globalBacked.configuration().weightItemsEnabled()).isFalse();
        assertThat(globalBacked.configuration().kotEnabled()).isFalse();

        // Missing global row as well → buildDefaultConfiguration: all enabled.
        when(appConfigurationRepository.findByBranchIdIsNull()).thenReturn(Optional.empty());
        ItemRequestSnapshot defaults = resolver.resolve(5L);
        assertThat(defaults.configuration().weightItemsEnabled()).isTrue();
        assertThat(defaults.configuration().servicesEnabled()).isTrue();
        assertThat(defaults.configuration().recipeItemsEnabled()).isTrue();
        assertThat(defaults.configuration().kotEnabled()).isTrue();
    }

    // ---------------------------------------------------------------
    // B. Branch scopes
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branchId 0 and null resolve the global row but stay distinct scopes in the snapshot")
    void zeroAndNullBranchIdsAreDistinctScopes() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(false, true, true, true)));
        when(appConfigurationRepository.findByBranchId(5L))
                .thenReturn(Optional.of(configuration(true, true, true, false)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        ItemRequestSnapshot concrete = resolver.resolve(5L);
        ItemRequestSnapshot zero = resolver.resolve(0L);
        ItemRequestSnapshot unscoped = resolver.resolve(null);

        // Configuration: 0 and null both normalize to the global row, the
        // concrete id keeps its own row.
        assertThat(concrete.configuration().weightItemsEnabled()).isTrue();
        assertThat(concrete.configuration().kotEnabled()).isFalse();
        assertThat(zero.configuration().weightItemsEnabled()).isFalse();
        assertThat(zero.configuration().kotEnabled()).isTrue();
        assertThat(unscoped.configuration().weightItemsEnabled()).isFalse();

        // Scope: the three are preserved verbatim and are not collapsed.
        assertThat(concrete.branchId()).isEqualTo(5L);
        assertThat(zero.branchId()).isZero();
        assertThat(unscoped.branchId()).isNull();

        verify(appConfigurationRepository, times(2)).findByBranchIdIsNull();
        verify(appConfigurationRepository, times(1)).findByBranchId(5L);
        verify(appConfigurationRepository, never()).findByBranchId(0L);
    }

    // ---------------------------------------------------------------
    // C. Tenant plan
    // ---------------------------------------------------------------

    @Test
    @DisplayName("plan restrictions in the snapshot match the legacy isItemTypeEnabled decisions")
    void planRestrictionsMatchLegacyChecks() {
        List<String> plans = List.of("FREE", "STANDARD", "PRO", "TEST_PLAN_X");
        for (String planName : plans) {
            TenantContext.setTenant("tenant-a");
            when(appConfigurationRepository.findByBranchIdIsNull())
                    .thenReturn(Optional.of(configuration(true, true, true, true)));
            when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                    .thenReturn(Optional.of(subscriptionWithPlan(planName)));

            ItemRequestSnapshot snapshot = resolver.resolve(null);
            assertThat(snapshot.plan().planName()).isEqualTo(planName);
            for (ItemType itemType : ItemType.values()) {
                assertThat(snapshot.isItemTypeEnabled(itemType))
                        .as("item type %s on plan %s", itemType, planName)
                        .isEqualTo(appConfigurationService.isItemTypeEnabled(itemType, null));
            }
            assertThat(snapshot.isKotEnabled())
                    .isEqualTo(appConfigurationService.isKotEnabled(null));
        }
    }

    @Test
    @DisplayName("the plan name is normalized the way currentPlanName normalizes it")
    void planNameIsTrimmedAndUppercased() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("  free  ")));

        ItemRequestSnapshot snapshot = resolver.resolve(null);

        assertThat(snapshot.plan().planName()).isEqualTo("FREE");
        // FREE supports none of the gated item types.
        assertThat(snapshot.plan().supportsWeightItems()).isFalse();
        assertThat(snapshot.plan().supportsServices()).isFalse();
        assertThat(snapshot.plan().supportsRecipeItems()).isFalse();
        assertThat(snapshot.isItemTypeEnabled(ItemType.WEIGHT)).isFalse();
    }

    @Test
    @DisplayName("each tenant gets its own plan snapshot; nothing leaks from one tenant to the next")
    void tenantsGetTheirOwnPlanSnapshots() {
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("FREE")));
        when(tenantSubscriptionRepository.findByTenantId("tenant-b"))
                .thenReturn(Optional.of(subscriptionWithPlan("STANDARD")));

        ItemRequestSnapshot tenantA = TenantContext.callWith("tenant-a", () -> resolver.resolve(null));
        ItemRequestSnapshot tenantB = TenantContext.callWith("tenant-b", () -> resolver.resolve(null));

        assertThat(tenantA.tenantId()).isEqualTo("tenant-a");
        assertThat(tenantA.plan().planName()).isEqualTo("FREE");
        assertThat(tenantA.plan().supportsWeightItems()).isFalse();

        assertThat(tenantB.tenantId()).isEqualTo("tenant-b");
        assertThat(tenantB.plan().planName()).isEqualTo("STANDARD");
        // STANDARD supports weight/services but not recipe items.
        assertThat(tenantB.plan().supportsWeightItems()).isTrue();
        assertThat(tenantB.plan().supportsServices()).isTrue();
        assertThat(tenantB.plan().supportsRecipeItems()).isFalse();

        // The first snapshot is untouched by the second resolution.
        assertThat(tenantA.plan().planName()).isEqualTo("FREE");
        verify(tenantSubscriptionRepository, times(1)).findByTenantId("tenant-a");
        verify(tenantSubscriptionRepository, times(1)).findByTenantId("tenant-b");
    }

    // ---------------------------------------------------------------
    // D + E. Request isolation and immutability
    // ---------------------------------------------------------------

    @Test
    @DisplayName("separate requests get separate snapshots and the resolver keeps no request state")
    void snapshotsDoNotShareStateAndResolverRetainsNothing() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchId(1L))
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(appConfigurationRepository.findByBranchId(2L))
                .thenReturn(Optional.of(configuration(false, false, false, false)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("PRO")));

        ItemRequestSnapshot first = resolver.resolve(1L);
        ItemRequestSnapshot second = resolver.resolve(2L);

        assertThat(first).isNotSameAs(second);
        assertThat(first.configuration().weightItemsEnabled()).isTrue();
        assertThat(second.configuration().weightItemsEnabled()).isFalse();

        // A singleton factory must not hold request-specific state.
        for (Field field : ItemRequestSnapshotResolver.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("resolver field %s", field.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the snapshot is an immutable value object: records, no setters, scalar components only")
    void snapshotIsImmutable() {
        List<Class<?>> valueTypes = List.of(
                ItemRequestSnapshot.class,
                ItemRequestSnapshot.Configuration.class,
                ItemRequestSnapshot.TenantPlan.class);
        for (Class<?> type : valueTypes) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set")))
                    .as("setter methods on %s", type.getSimpleName())
                    .isEmpty();
        }
        // The only components are primitives, String, and the nested records —
        // no mutable JPA entity or collection can hide inside the snapshot.
        assertThat(Arrays.stream(ItemRequestSnapshot.class.getRecordComponents())
                .map(component -> component.getType())
                .allMatch(type -> type.isPrimitive()
                        || type == String.class
                        || type == Long.class
                        || valueTypes.contains(type)))
                .isTrue();
        assertThat(Arrays.stream(ItemRequestSnapshot.Configuration.class.getRecordComponents())
                .allMatch(component -> component.getType() == boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("item-type enablement mirrors the legacy switch exactly, including VOLUME alongside WEIGHT")
    void itemTypeMirrorMatchesLegacySwitch() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, false, false, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("TEST_PLAN_X")));

        ItemRequestSnapshot snapshot = resolver.resolve(null);

        assertThat(snapshot.isItemTypeEnabled(ItemType.NORMAL)).isTrue();
        assertThat(snapshot.isItemTypeEnabled(ItemType.WEIGHT)).isTrue();
        assertThat(snapshot.isItemTypeEnabled(ItemType.VOLUME)).isTrue();
        assertThat(snapshot.isItemTypeEnabled(ItemType.SERVICE)).isFalse();
        assertThat(snapshot.isItemTypeEnabled(ItemType.RECIPE)).isFalse();
        assertThat(snapshot.isKotEnabled()).isTrue();

        // The legacy service agrees on every type for the same state.
        for (ItemType itemType : ItemType.values()) {
            assertThat(snapshot.isItemTypeEnabled(itemType))
                    .isEqualTo(appConfigurationService.isItemTypeEnabled(itemType, null));
        }
    }

    @Test
    @DisplayName("plan and configuration are resolved independently: plan gating still applies on an enabled flag")
    void planGatingAppliesOverEnabledFlags() {
        TenantContext.setTenant("tenant-a");
        when(appConfigurationRepository.findByBranchIdIsNull())
                .thenReturn(Optional.of(configuration(true, true, true, true)));
        when(tenantSubscriptionRepository.findByTenantId("tenant-a"))
                .thenReturn(Optional.of(subscriptionWithPlan("FREE")));

        ItemRequestSnapshot snapshot = resolver.resolve(null);

        // Everything is enabled in configuration, but the FREE plan gates it off.
        assertThat(snapshot.configuration().weightItemsEnabled()).isTrue();
        assertThat(snapshot.isItemTypeEnabled(ItemType.WEIGHT)).isFalse();
        assertThat(snapshot.isItemTypeEnabled(ItemType.SERVICE)).isFalse();
        assertThat(snapshot.isItemTypeEnabled(ItemType.RECIPE)).isFalse();
        // NORMAL is never gated.
        assertThat(snapshot.isItemTypeEnabled(ItemType.NORMAL)).isTrue();
        // KOT is a configuration-only decision and stays on.
        assertThat(snapshot.isKotEnabled()).isTrue();
    }
}
