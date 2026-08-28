package com.chala.posapp.service;

import com.chala.posapp.dto.saas.module.*;
import com.chala.posapp.entity.PlanModule;
import com.chala.posapp.entity.ShopBusinessType;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantModule;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleDefinition;
import com.chala.posapp.module.ModulePitch;
import com.chala.posapp.module.ModulePresets;
import com.chala.posapp.module.ModuleRoute;
import com.chala.posapp.repository.PlanModuleRepository;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.repository.TenantModuleRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the super admin panel does to modules, for one shop or for a whole plan.
 *
 * <p>All of it runs against the control-plane database; callers reach it through
 * {@code SuperAdminModuleController}, which is already {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminModuleService {

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final PlanModuleRepository planModuleRepository;
    private final TenantModuleRepository tenantModuleRepository;
    private final ModuleAccessService moduleAccessService;
    private final SuperAdminAuditService auditService;
    private final AuthService authService;

    // ------------------------------------------------------------------ catalog

    /** The catalog on its own, with a per-module count of how many shops deviate from their plan. */
    @Transactional(readOnly = true)
    public ModuleCatalogResponse getCatalog() {
        Map<String, Long> overrideCounts = tenantModuleRepository.countOverridesByModule().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1], (a, b) -> a));

        Map<String, List<ModuleCatalogResponse.Entry>> byCategory = new LinkedHashMap<>();
        for (ModuleDefinition definition : ModuleCatalog.topLevel()) {
            List<ModuleCatalogResponse.Entry> children = ModuleCatalog.childrenOf(definition.key()).stream()
                    .map(child -> toCatalogEntry(child, overrideCounts, List.of()))
                    .toList();
            ModuleCatalogResponse.Entry entry = toCatalogEntry(definition, overrideCounts, children);
            byCategory.computeIfAbsent(definition.category().name(), key -> new ArrayList<>()).add(entry);
        }

        List<ModuleCatalogResponse.CategoryGroup> groups = byCategory.entrySet().stream()
                .map(entry -> new ModuleCatalogResponse.CategoryGroup(
                        entry.getKey(),
                        com.chala.posapp.module.ModuleCategory.valueOf(entry.getKey()).getLabel(),
                        entry.getValue()))
                .toList();

        return new ModuleCatalogResponse(groups, ModuleCatalog.all().size());
    }

    private ModuleCatalogResponse.Entry toCatalogEntry(ModuleDefinition definition,
                                                       Map<String, Long> overrideCounts,
                                                       List<ModuleCatalogResponse.Entry> children) {
        return new ModuleCatalogResponse.Entry(
                definition.key(),
                definition.parentKey(),
                definition.name(),
                definition.description(),
                definition.icon(),
                definition.locked(),
                overrideCounts.getOrDefault(definition.key(), 0L),
                definition.routes().stream().map(ModuleRoute::pattern).toList(),
                definition.uiPaths(),
                children
        );
    }

    // ------------------------------------------------------------------ per shop

    @Transactional(readOnly = true)
    public TenantModulesResponse getTenantModules(String tenantId) {
        TenantSubscription subscription = requireSubscription(tenantId);
        return buildTenantResponse(subscription);
    }

    /**
     * Applies a batch of toggles to one shop.
     *
     * <p>A change with {@code enabled == null} deletes the override row so the shop follows its
     * plan again. A change that would set the same value the plan already gives also deletes the
     * row rather than storing a redundant one — otherwise a later plan edit would not reach a
     * shop that had merely been "confirmed" at some point.
     */
    @Transactional
    public TenantModulesResponse updateTenantModules(String tenantId, ModuleToggleRequest request) {
        TenantSubscription subscription = requireSubscription(tenantId);
        String actor = currentActor();
        Map<String, Boolean> planDefaults = planDefaultsFor(subscription);

        List<String> applied = new ArrayList<>();
        for (ModuleToggleRequest.Change change : request.changes()) {
            String key = change.moduleKey();
            ModuleDefinition definition = ModuleCatalog.byKey(key);
            if (definition == null) {
                throw new BadRequestException("Unknown module: " + key);
            }
            if (definition.locked()) {
                throw new BadRequestException(definition.name() + " is a core module and cannot be switched off.");
            }

            boolean planSays = planDefaults.getOrDefault(key, true);
            Optional<TenantModule> existing = tenantModuleRepository.findByTenantIdAndModuleKey(tenantId, key);

            if (change.enabled() == null || change.enabled() == planSays) {
                existing.ifPresent(tenantModuleRepository::delete);
                applied.add(key + "=plan(" + (planSays ? "on" : "off") + ")");
                continue;
            }

            TenantModule row = existing.orElseGet(() -> TenantModule.builder()
                    .tenantId(tenantId)
                    .moduleKey(key)
                    .build());
            row.setEnabled(change.enabled());
            row.setNote(trimToNull(request.note()));
            row.setUpdatedBy(actor);
            tenantModuleRepository.save(row);
            applied.add(key + "=" + (change.enabled() ? "on" : "off"));
        }

        moduleAccessService.invalidate(tenantId);

        auditService.record(actor, "SHOP_MODULES_UPDATED", SuperAdminAuditService.TARGET_SHOP, tenantId,
                subscription.getShopName() + ": " + String.join(", ", applied),
                toJsonArray(applied));

        return buildTenantResponse(subscription);
    }

    /** Drops every override so the shop follows its plan template exactly. */
    @Transactional
    public TenantModulesResponse resetTenantToPlan(String tenantId) {
        TenantSubscription subscription = requireSubscription(tenantId);
        long removed = tenantModuleRepository.countByTenantId(tenantId);
        tenantModuleRepository.deleteByTenantId(tenantId);
        moduleAccessService.invalidate(tenantId);

        auditService.record(currentActor(), "SHOP_MODULES_RESET", SuperAdminAuditService.TARGET_SHOP, tenantId,
                subscription.getShopName() + ": cleared " + removed + " module override(s), back to plan "
                        + planNameOf(subscription));

        return buildTenantResponse(subscription);
    }

    /**
     * Seeds a newly onboarded shop. The plan template covers the bulk; the business type adds a
     * few explicit overrides (a retail shop does not want the table map) so the panel shows why
     * the shop differs from a bare plan.
     */
    @Transactional
    public void applyOnboardingPreset(String tenantId, ShopBusinessType businessType) {
        Map<String, Boolean> overrides = ModulePresets.forBusinessType(businessType);
        if (overrides.isEmpty()) {
            return;
        }
        String actor = currentActor();
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
        Map<String, Boolean> planDefaults = subscription == null ? Map.of() : planDefaultsFor(subscription);

        overrides.forEach((key, value) -> {
            if (!ModuleCatalog.exists(key)) {
                return;
            }
            if (planDefaults.getOrDefault(key, true) == value) {
                return; // plan already agrees; no override needed
            }
            tenantModuleRepository.save(TenantModule.builder()
                    .tenantId(tenantId)
                    .moduleKey(key)
                    .enabled(value)
                    .note(businessType + " preset")
                    .updatedBy(actor)
                    .build());
        });
        moduleAccessService.invalidate(tenantId);
    }

    // ------------------------------------------------------------------ per plan

    @Transactional(readOnly = true)
    public PlanModulesResponse getPlanModules(Long planId) {
        SubscriptionPlan plan = requirePlan(planId);
        Map<String, Boolean> planDefaults = toMap(planModuleRepository.findByPlanId(planId));
        Set<String> effective = moduleAccessService.computeEnabled(planDefaults, Map.of());

        List<ModuleNodeResponse> tree = buildTree(planDefaults, Map.of(), effective, Map.of());
        long shopsOnPlan = tenantSubscriptionRepository.countByPlanId(planId);

        return new PlanModulesResponse(
                plan.getId(), plan.getName(), shopsOnPlan,
                effective.size(), ModuleCatalog.all().size(), tree);
    }

    /**
     * Edits a plan template. Every shop on the plan without its own override is affected, which
     * is why the whole module cache is dropped rather than one tenant's entry.
     */
    @Transactional
    public PlanModulesResponse updatePlanModules(Long planId, ModuleToggleRequest request) {
        SubscriptionPlan plan = requirePlan(planId);
        String actor = currentActor();

        List<String> applied = new ArrayList<>();
        for (ModuleToggleRequest.Change change : request.changes()) {
            String key = change.moduleKey();
            ModuleDefinition definition = ModuleCatalog.byKey(key);
            if (definition == null) {
                throw new BadRequestException("Unknown module: " + key);
            }
            if (definition.locked()) {
                throw new BadRequestException(definition.name() + " is a core module and cannot be switched off.");
            }
            boolean enabled = change.enabled() != null && change.enabled();

            PlanModule row = planModuleRepository.findByPlanIdAndModuleKey(planId, key)
                    .orElseGet(() -> PlanModule.builder().planId(planId).moduleKey(key).build());
            row.setEnabled(enabled);
            planModuleRepository.save(row);
            applied.add(key + "=" + (enabled ? "on" : "off"));
        }

        // A plan edit can reach any shop, so no per-tenant invalidation is enough.
        moduleAccessService.invalidateAll();

        long affected = tenantSubscriptionRepository.countByPlanId(planId);
        auditService.record(actor, "PLAN_MODULES_UPDATED", SuperAdminAuditService.TARGET_PLAN,
                String.valueOf(planId),
                "Plan " + plan.getName() + " (" + affected + " shop(s)): " + String.join(", ", applied),
                toJsonArray(applied));

        return getPlanModules(planId);
    }

    // ------------------------------------------------------------------ tenant-facing

    /**
     * What the POS app fetches at login. Runs in whatever tenant context the caller is in, so it
     * is safe to expose to a shop admin — it can only ever describe their own shop.
     */
    @Transactional(readOnly = true)
    public MyModulesResponse getMyModules() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            throw new BadRequestException("No active shop context found!");
        }
        ModuleAccessService.ModuleSnapshot snapshot = moduleAccessService.snapshotFor(tenantId);

        Map<String, String> routeModule = new LinkedHashMap<>();
        for (ModuleDefinition definition : ModuleCatalog.all()) {
            for (String uiPath : definition.uiPaths()) {
                routeModule.put(uiPath, definition.key());
            }
        }

        // The catalog rides along so a screen can say "Reports isn't in your package"
        // rather than printing a raw key at a shop owner.
        List<MyModulesResponse.CatalogEntry> catalog = ModuleCatalog.all().stream()
                .map(definition -> {
                    ModulePitch pitch = ModulePitch.forModule(definition.key());
                    return new MyModulesResponse.CatalogEntry(
                            definition.key(),
                            definition.parentKey(),
                            definition.name(),
                            definition.description(),
                            definition.category().name(),
                            definition.category().getLabel(),
                            definition.locked(),
                            snapshot.isEnabled(definition.key()),
                            pitch == null ? null : pitch.headline(),
                            pitch == null ? null : pitch.pitch(),
                            pitch == null ? List.of() : pitch.outcomes());
                })
                .toList();

        return new MyModulesResponse(
                tenantId,
                snapshot.planName(),
                List.copyOf(snapshot.enabledKeys()),
                routeModule,
                catalog);
    }

    // ------------------------------------------------------------------ internals

    private TenantModulesResponse buildTenantResponse(TenantSubscription subscription) {
        String tenantId = subscription.getTenantId();
        Map<String, Boolean> planDefaults = planDefaultsFor(subscription);
        List<TenantModule> overrideRows = tenantModuleRepository.findByTenantId(tenantId);
        Map<String, Boolean> overrides = toTenantMap(overrideRows);
        Map<String, String> overrideNotes = overrideRows.stream()
                .filter(row -> row.getNote() != null)
                .collect(Collectors.toMap(TenantModule::getModuleKey, TenantModule::getNote, (a, b) -> a));

        Set<String> effective = moduleAccessService.computeEnabled(planDefaults, overrides);
        List<ModuleNodeResponse> tree = buildTree(planDefaults, overrides, effective, overrideNotes);

        int overrideCount = (int) overrides.entrySet().stream()
                .filter(entry -> planDefaults.getOrDefault(entry.getKey(), true) != entry.getValue())
                .count();

        return new TenantModulesResponse(
                tenantId,
                subscription.getShopName(),
                subscription.getPlan() != null ? subscription.getPlan().getId() : null,
                planNameOf(subscription),
                subscription.getBusinessType() != null ? subscription.getBusinessType().name() : null,
                effective.size(),
                ModuleCatalog.all().size(),
                overrideCount,
                tree);
    }

    private List<ModuleNodeResponse> buildTree(Map<String, Boolean> planDefaults,
                                               Map<String, Boolean> overrides,
                                               Set<String> effective,
                                               Map<String, String> overrideNotes) {
        // The plan's own view, ignoring tenant overrides — that is what "planEnabled" means,
        // so the panel can show "plan says on, this shop has it off".
        Set<String> planEffective = moduleAccessService.computeEnabled(planDefaults, Map.of());

        List<ModuleNodeResponse> roots = new ArrayList<>();
        for (ModuleDefinition definition : ModuleCatalog.topLevel()) {
            List<ModuleNodeResponse> children = ModuleCatalog.childrenOf(definition.key()).stream()
                    .map(child -> toNode(child, planDefaults, overrides, effective, planEffective, overrideNotes,
                            List.of()))
                    .toList();
            roots.add(toNode(definition, planDefaults, overrides, effective, planEffective, overrideNotes, children));
        }
        return roots;
    }

    private ModuleNodeResponse toNode(ModuleDefinition definition,
                                      Map<String, Boolean> planDefaults,
                                      Map<String, Boolean> overrides,
                                      Set<String> effective,
                                      Set<String> planEffective,
                                      Map<String, String> overrideNotes,
                                      List<ModuleNodeResponse> children) {
        String key = definition.key();
        boolean planEnabled = planEffective.contains(key);
        boolean enabled = effective.contains(key);
        boolean overridden = overrides.containsKey(key) && overrides.get(key) != planDefaults.getOrDefault(key, true);

        String source = definition.locked()
                ? ModuleNodeResponse.SOURCE_LOCKED
                : overridden ? ModuleNodeResponse.SOURCE_TENANT : ModuleNodeResponse.SOURCE_PLAN;

        return new ModuleNodeResponse(
                key,
                definition.parentKey(),
                definition.name(),
                definition.description(),
                definition.category().name(),
                definition.category().getLabel(),
                definition.icon(),
                definition.locked(),
                enabled,
                planEnabled,
                overridden,
                source,
                overrideNotes.get(key),
                children);
    }

    private Map<String, Boolean> planDefaultsFor(TenantSubscription subscription) {
        if (subscription.getPlan() == null) {
            return Map.of();
        }
        Map<String, Boolean> stored = toMap(planModuleRepository.findByPlanId(subscription.getPlan().getId()));
        if (!stored.isEmpty()) {
            return stored;
        }
        // Plan predates the registry and the seeder has not run against it yet.
        return ModulePresets.forPlanName(subscription.getPlan().getName());
    }

    private static Map<String, Boolean> toMap(List<PlanModule> rows) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (PlanModule row : rows) {
            if (ModuleCatalog.exists(row.getModuleKey())) {
                map.put(row.getModuleKey(), row.isEnabled());
            }
        }
        return map;
    }

    private static Map<String, Boolean> toTenantMap(List<TenantModule> rows) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (TenantModule row : rows) {
            if (ModuleCatalog.exists(row.getModuleKey())) {
                map.put(row.getModuleKey(), row.isEnabled());
            }
        }
        return map;
    }

    private TenantSubscription requireSubscription(String tenantId) {
        return tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + tenantId));
    }

    private SubscriptionPlan requirePlan(Long planId) {
        return subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
    }

    private String planNameOf(TenantSubscription subscription) {
        return subscription.getPlan() != null ? subscription.getPlan().getName() : "N/A";
    }

    private String currentActor() {
        try {
            return authService.getLoggedUser().getUsername();
        } catch (Exception exception) {
            return "system";
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
