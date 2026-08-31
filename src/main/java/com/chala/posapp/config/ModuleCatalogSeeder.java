package com.chala.posapp.config;

import com.chala.posapp.entity.AppModule;
import com.chala.posapp.entity.PlanModule;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleDefinition;
import com.chala.posapp.module.ModulePresets;
import com.chala.posapp.repository.AppModuleRepository;
import com.chala.posapp.repository.PlanModuleRepository;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.service.ModuleAccessService;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors the code module catalog into the control-plane database and gives every plan a
 * starting module template.
 *
 * <p>Runs after {@code SubscriptionPlanSeeder} (@Order(2)) so the three system plans exist.
 *
 * <p>Deliberately non-destructive on re-run:
 * <ul>
 *   <li>Catalog rows are upserted — structure comes from code, always.</li>
 *   <li>A module dropped from the code catalog is marked {@code active = false} rather than
 *       deleted, so existing {@code plan_modules} / {@code tenant_modules} rows survive a
 *       rename mistake and can be recovered.</li>
 *   <li>A plan that already has rows is only <em>topped up</em> with keys it has never seen.
 *       An admin who switched a module off in the panel does not get it switched back on at
 *       the next restart.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class ModuleCatalogSeeder implements CommandLineRunner {

    private final AppModuleRepository appModuleRepository;
    private final PlanModuleRepository planModuleRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final ModuleAccessService moduleAccessService;

    @Override
    public void run(String... args) {
        TenantContext.runWith("MASTER", this::sync);
    }

    // No @Transactional: a self-invoked call is not proxied, and each repository save is
    // already its own transaction. Matches how SubscriptionPlanSeeder seeds.
    private void sync() {
        syncCatalog();
        syncPlanTemplates();
        moduleAccessService.invalidateAll();
    }

    private void syncCatalog() {
        Set<String> codeKeys = new HashSet<>(ModuleCatalog.keys());

        for (ModuleDefinition definition : ModuleCatalog.all()) {
            AppModule row = appModuleRepository.findByModuleKey(definition.key())
                    .orElseGet(AppModule::new);
            row.setModuleKey(definition.key());
            row.setParentKey(definition.parentKey());
            row.setName(definition.name());
            row.setDescription(definition.description());
            row.setCategory(definition.category());
            row.setIcon(definition.icon());
            row.setLocked(definition.locked());
            row.setDefaultEnabled(true);
            row.setDisplayOrder(ModuleCatalog.displayOrder(definition.key()));
            row.setActive(true);
            appModuleRepository.save(row);
        }

        List<AppModule> stale = appModuleRepository.findAll().stream()
                .filter(row -> !codeKeys.contains(row.getModuleKey()))
                .filter(AppModule::isActive)
                .toList();
        for (AppModule row : stale) {
            row.setActive(false);
            appModuleRepository.save(row);
            log.warn("Module '{}' is no longer in ModuleCatalog — marked inactive, override rows kept.",
                    row.getModuleKey());
        }

        log.info("Module catalog synced: {} modules ({} inactive).", ModuleCatalog.all().size(), stale.size());
    }

    private void syncPlanTemplates() {
        for (SubscriptionPlan plan : subscriptionPlanRepository.findAll()) {
            Map<String, Boolean> preset = ModulePresets.forPlanName(plan.getName());
            List<PlanModule> existing = planModuleRepository.findByPlanId(plan.getId());
            Set<String> known = existing.stream().map(PlanModule::getModuleKey).collect(java.util.stream.Collectors.toSet());

            int added = 0;
            for (ModuleDefinition definition : ModuleCatalog.all()) {
                if (known.contains(definition.key())) {
                    continue;
                }
                planModuleRepository.save(PlanModule.builder()
                        .planId(plan.getId())
                        .moduleKey(definition.key())
                        .enabled(preset.getOrDefault(definition.key(), true))
                        .build());
                added++;
            }
            if (added > 0) {
                log.info("Plan '{}': seeded {} module rows.", plan.getName(), added);
            }
        }
    }
}
