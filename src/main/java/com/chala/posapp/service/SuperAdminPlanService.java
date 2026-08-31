package com.chala.posapp.service;

import com.chala.posapp.dto.saas.PlanRequest;
import com.chala.posapp.dto.saas.PlanResponse;
import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.PlanModule;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleDefinition;
import com.chala.posapp.repository.PlanModuleRepository;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plan CRUD for the super admin panel.
 *
 * <p>Before this, plans could only be created by editing {@code SubscriptionPlanSeeder} and
 * restarting. The three seeded plans stay marked {@code systemPlan} and are protected from
 * deletion and rename, because {@code ModulePresets} and the legacy plan-name checks elsewhere
 * still key off those exact strings.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminPlanService {

    private final SubscriptionPlanRepository planRepository;
    private final PlanModuleRepository planModuleRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final ModuleAccessService moduleAccessService;
    private final SuperAdminAuditService auditService;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public List<PlanResponse> getAllPlans(boolean includeInactive) {
        Map<Long, Long> shopCounts = tenantSubscriptionRepository.countGroupedByPlan().stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1], (a, b) -> a));

        return planRepository.findAll().stream()
                .filter(plan -> includeInactive || plan.isActive())
                .sorted(Comparator.comparingInt(SubscriptionPlan::getDisplayOrder)
                        .thenComparing(SubscriptionPlan::getName))
                .map(plan -> toResponse(plan, shopCounts.getOrDefault(plan.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(Long planId) {
        SubscriptionPlan plan = require(planId);
        return toResponse(plan, tenantSubscriptionRepository.countByPlanId(planId));
    }

    @Transactional
    public PlanResponse createPlan(PlanRequest request) {
        String name = normalizeName(request.name());
        if (planRepository.findByName(name).isPresent()) {
            throw new AlreadyExistsException("A plan named " + name + " already exists");
        }

        SubscriptionPlan plan = new SubscriptionPlan();
        apply(plan, request, name);
        plan.setSystemPlan(false);
        planRepository.save(plan);

        seedModulesFor(plan, request.copyModulesFromPlanId());

        auditService.record(actor(), "PLAN_CREATED", SuperAdminAuditService.TARGET_PLAN,
                String.valueOf(plan.getId()),
                "Created plan " + name + " (" + plan.getBillingCycle() + ", " + plan.getRenewalPrice() + ")");

        moduleAccessService.invalidateAll();
        return toResponse(plan, 0L);
    }

    @Transactional
    public PlanResponse updatePlan(Long planId, PlanRequest request) {
        SubscriptionPlan plan = require(planId);
        String name = normalizeName(request.name());

        if (plan.isSystemPlan() && !plan.getName().equals(name)) {
            // FREE/STANDARD/PRO are matched by name in ModulePresets and in the legacy
            // plan checks the POS app still carries. Renaming one silently re-tiers every
            // shop on it.
            throw new BadRequestException("Built-in plans cannot be renamed. Create a new plan instead.");
        }
        planRepository.findByName(name)
                .filter(existing -> !existing.getId().equals(planId))
                .ifPresent(existing -> {
                    throw new AlreadyExistsException("A plan named " + name + " already exists");
                });

        String before = describe(plan);
        apply(plan, request, name);
        planRepository.save(plan);

        auditService.record(actor(), "PLAN_UPDATED", SuperAdminAuditService.TARGET_PLAN,
                String.valueOf(planId),
                "Updated plan " + name + ": " + before + " → " + describe(plan));

        moduleAccessService.invalidateAll();
        return toResponse(plan, tenantSubscriptionRepository.countByPlanId(planId));
    }

    @Transactional
    public void deletePlan(Long planId) {
        SubscriptionPlan plan = require(planId);
        if (plan.isSystemPlan()) {
            throw new BadRequestException("Built-in plans cannot be deleted. Deactivate it instead.");
        }
        long shops = tenantSubscriptionRepository.countByPlanId(planId);
        if (shops > 0) {
            throw new BadRequestException(
                    shops + " shop(s) are still on this plan. Move them to another plan first.");
        }
        planModuleRepository.deleteByPlanId(planId);
        planRepository.delete(plan);

        auditService.record(actor(), "PLAN_DELETED", SuperAdminAuditService.TARGET_PLAN,
                String.valueOf(planId), "Deleted plan " + plan.getName());
        moduleAccessService.invalidateAll();
    }

    private void seedModulesFor(SubscriptionPlan plan, Long copyFromPlanId) {
        Map<String, Boolean> source = copyFromPlanId == null
                ? Map.of()
                : planModuleRepository.findByPlanId(copyFromPlanId).stream()
                        .collect(Collectors.toMap(PlanModule::getModuleKey, PlanModule::isEnabled, (a, b) -> a));

        for (ModuleDefinition definition : ModuleCatalog.all()) {
            planModuleRepository.save(PlanModule.builder()
                    .planId(plan.getId())
                    .moduleKey(definition.key())
                    // No source plan means a new plan starts fully enabled and the admin
                    // switches off what the package excludes.
                    .enabled(source.getOrDefault(definition.key(), true))
                    .build());
        }
    }

    private void apply(SubscriptionPlan plan, PlanRequest request, String name) {
        plan.setName(name);
        plan.setBillingCycle(BillingCycle.valueOf(request.billingCycle().toUpperCase(Locale.ROOT)));
        plan.setInitialPrice(request.initialPrice());
        plan.setRenewalPrice(request.renewalPrice());
        plan.setMaxBranches(request.maxBranches());
        plan.setDescription(trimToNull(request.description()));
        plan.setColor(trimToNull(request.color()));
        if (request.displayOrder() != null) {
            plan.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            plan.setActive(request.active());
        }
    }

    private PlanResponse toResponse(SubscriptionPlan plan, long shopCount) {
        List<PlanModule> modules = planModuleRepository.findByPlanId(plan.getId());
        Map<String, Boolean> defaults = modules.stream()
                .filter(row -> ModuleCatalog.exists(row.getModuleKey()))
                .collect(Collectors.toMap(PlanModule::getModuleKey, PlanModule::isEnabled, (a, b) -> a));
        int enabledCount = moduleAccessService.computeEnabled(defaults, Map.of()).size();

        return new PlanResponse(
                plan.getId(),
                plan.getName(),
                plan.getBillingCycle().name(),
                plan.getInitialPrice(),
                plan.getRenewalPrice(),
                monthlyValue(plan),
                plan.getMaxBranches(),
                plan.getDescription(),
                plan.getColor(),
                plan.getDisplayOrder(),
                plan.isActive(),
                plan.isSystemPlan(),
                shopCount,
                enabledCount,
                ModuleCatalog.all().size());
    }

    /** Yearly plans are divided by 12 so MRR is comparable across billing cycles. */
    public static double monthlyValue(SubscriptionPlan plan) {
        if (plan == null) {
            return 0.0;
        }
        return plan.getBillingCycle() == BillingCycle.YEARLY
                ? plan.getRenewalPrice() / 12.0
                : plan.getRenewalPrice();
    }

    private String describe(SubscriptionPlan plan) {
        return plan.getBillingCycle() + "/" + plan.getRenewalPrice() + "/" + plan.getMaxBranches() + "br"
                + (plan.isActive() ? "" : "/inactive");
    }

    private SubscriptionPlan require(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
    }

    private String normalizeName(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String actor() {
        try {
            return authService.getLoggedUser().getUsername();
        } catch (Exception exception) {
            return "system";
        }
    }
}
