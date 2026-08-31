package com.chala.posapp.service;

import com.chala.posapp.entity.PlanModule;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantModule;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleDefinition;
import com.chala.posapp.repository.PlanModuleRepository;
import com.chala.posapp.repository.TenantModuleRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves which modules a shop may actually use, and caches the answer.
 *
 * <p>Three layers, most specific first:
 * <ol>
 *   <li>{@code tenant_modules} — an explicit override for this one shop</li>
 *   <li>{@code plan_modules} — the plan's template</li>
 *   <li>the code catalog — enabled, so a module added after a plan's rows were written does
 *       not silently vanish for every existing shop</li>
 * </ol>
 *
 * <p>A child module is only usable when its parent is too: switching off {@code STOCK} takes
 * transfers, adjustments and processing with it whatever their own rows say. {@code locked}
 * modules ({@code ITEMS}, {@code SETTINGS}) are always on — the app cannot boot without them.
 *
 * <p>Resolution hits the control-plane database, so it is cached per tenant for
 * {@link #CACHE_TTL_MILLIS}. Every write path in {@link SuperAdminModuleService} calls
 * {@link #invalidate(String)}, so the TTL only matters for changes made directly in SQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleAccessService {

    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PlanModuleRepository planModuleRepository;
    private final TenantModuleRepository tenantModuleRepository;
    private final PlatformTransactionManager transactionManager;

    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * The effective module state for a shop.
     *
     * @param enabledKeys every module key the shop may use, parents already applied
     * @param overrides   module keys where the shop deviates from its plan, and in which direction
     */
    public record ModuleSnapshot(
            String tenantId,
            Long planId,
            String planName,
            Set<String> enabledKeys,
            Map<String, Boolean> overrides
    ) {
        public boolean isEnabled(String moduleKey) {
            return enabledKeys.contains(moduleKey);
        }
    }

    public ModuleSnapshot snapshotFor(String tenantId) {
        CachedSnapshot cached = cache.get(tenantId);
        if (cached != null && !cached.isStale()) {
            return cached.snapshot();
        }
        ModuleSnapshot fresh = resolve(tenantId);
        cache.put(tenantId, new CachedSnapshot(fresh, System.currentTimeMillis()));
        return fresh;
    }

    public boolean isEnabled(String tenantId, String moduleKey) {
        return snapshotFor(tenantId).isEnabled(moduleKey);
    }

    public void invalidate(String tenantId) {
        cache.remove(tenantId);
    }

    /** Used when a plan's template changes — every shop on that plan is affected. */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Resolves without the cache. Also used by the panel to preview what a plan change would do
     * before it is committed.
     */
    public ModuleSnapshot resolve(String tenantId) {
        return inMaster(() -> {
            Optional<TenantSubscription> subscription = tenantSubscriptionRepository.findByTenantId(tenantId);
            SubscriptionPlan plan = subscription.map(TenantSubscription::getPlan).orElse(null);
            Long planId = plan != null ? plan.getId() : null;
            String planName = plan != null ? plan.getName() : null;

            Map<String, Boolean> planDefaults = planId == null
                    ? Map.of()
                    : toMap(planModuleRepository.findByPlanId(planId),
                            PlanModule::getModuleKey, PlanModule::isEnabled);

            Map<String, Boolean> tenantOverrides = toMap(tenantModuleRepository.findByTenantId(tenantId),
                    TenantModule::getModuleKey, TenantModule::isEnabled);

            Set<String> enabled = computeEnabled(planDefaults, tenantOverrides);

            // Only report an override that actually differs from the plan; a stale row that
            // happens to agree with the plan is not a deviation worth flagging in the UI.
            Map<String, Boolean> realOverrides = new LinkedHashMap<>();
            tenantOverrides.forEach((key, value) -> {
                boolean planSays = planDefaults.getOrDefault(key, true);
                if (planSays != value) {
                    realOverrides.put(key, value);
                }
            });

            return new ModuleSnapshot(tenantId, planId, planName, enabled, realOverrides);
        });
    }

    /**
     * Applies the three-layer rule plus the parent cascade. Exposed so the panel can preview a
     * hypothetical plan/override combination without writing anything.
     */
    public Set<String> computeEnabled(Map<String, Boolean> planDefaults, Map<String, Boolean> tenantOverrides) {
        Map<String, Boolean> resolved = new HashMap<>();
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            String key = definition.key();
            boolean value;
            if (definition.locked()) {
                value = true;
            } else if (tenantOverrides.containsKey(key)) {
                value = tenantOverrides.get(key);
            } else if (planDefaults.containsKey(key)) {
                value = planDefaults.get(key);
            } else {
                value = true;
            }
            resolved.put(key, value);
        }

        // Catalog order guarantees a parent is resolved before its children.
        Set<String> enabled = new LinkedHashSet<>();
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            boolean own = resolved.getOrDefault(definition.key(), true);
            boolean parentOk = definition.parentKey() == null || enabled.contains(definition.parentKey());
            if (own && parentOk) {
                enabled.add(definition.key());
            }
        }
        return enabled;
    }

    /** The plan template as a map, used by the panel and by onboarding. */
    public Map<String, Boolean> planDefaults(Long planId) {
        if (planId == null) {
            return Map.of();
        }
        return inMaster(() -> toMap(planModuleRepository.findByPlanId(planId),
                PlanModule::getModuleKey, PlanModule::isEnabled));
    }

    private static <T> Map<String, Boolean> toMap(List<T> rows,
                                                  java.util.function.Function<T, String> keyFn,
                                                  java.util.function.Function<T, Boolean> valueFn) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (ModuleCatalog.exists(key)) {
                map.put(key, valueFn.apply(row));
            }
        }
        return map;
    }

    private <T> T inMaster(java.util.function.Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return TenantContext.callWith("MASTER", () -> tx.execute(status -> work.get()));
    }

    private record CachedSnapshot(ModuleSnapshot snapshot, long resolvedAt) {
        boolean isStale() {
            return System.currentTimeMillis() - resolvedAt > CACHE_TTL_MILLIS;
        }
    }
}
