package com.chala.posapp.service;

import com.chala.posapp.dto.saas.PublicPlanResponse;
import com.chala.posapp.entity.PlanModule;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.repository.PlanModuleRepository;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PlanModuleRepository planModuleRepository;
    private final ModuleAccessService moduleAccessService;
    private final PlatformTransactionManager transactionManager;

    /**
     * The packages a shop can buy, each carrying the modules it actually includes.
     *
     * <p>The module list is read from {@code plan_modules} — the same rows the API gate
     * enforces — so the pricing page cannot advertise something the server would refuse.
     */
    public List<PublicPlanResponse> getAllPlans() {
        return TenantContext.callWith("MASTER", () -> newMasterTx().execute(status ->
                planRepository.findAll().stream()
                        .filter(SubscriptionPlan::isActive)
                        .sorted(Comparator.comparingInt(SubscriptionPlan::getDisplayOrder)
                                .thenComparing(SubscriptionPlan::getRenewalPrice))
                        .map(this::toPublicPlan)
                        .toList()));
    }

    private PublicPlanResponse toPublicPlan(SubscriptionPlan plan) {
        Map<String, Boolean> defaults = planModuleRepository.findByPlanId(plan.getId()).stream()
                .filter(row -> ModuleCatalog.exists(row.getModuleKey()))
                .collect(java.util.stream.Collectors.toMap(
                        PlanModule::getModuleKey, PlanModule::isEnabled, (a, b) -> a));

        // computeEnabled applies the parent cascade and the locked-module rule, so the
        // advertised list is exactly what a shop on this plan would get.
        List<String> moduleKeys = List.copyOf(moduleAccessService.computeEnabled(defaults, Map.of()));

        return new PublicPlanResponse(
                plan.getId(),
                plan.getName(),
                humanLabel(plan.getName()),
                plan.getBillingCycle().name(),
                plan.getInitialPrice(),
                plan.getRenewalPrice(),
                plan.getMaxBranches(),
                plan.getDescription(),
                plan.getColor(),
                plan.getTrialDays(),
                moduleKeys,
                moduleKeys.size(),
                ModuleCatalog.all().size());
    }

    /** FREE -> Free, MONTHLY_PRO -> Monthly Pro. Plan names are stored SCREAMING_CASE. */
    private String humanLabel(String name) {
        if (name == null || name.isBlank()) {
            return "Package";
        }
        return java.util.Arrays.stream(name.split("_"))
                .filter(part -> !part.isEmpty())
                .map(part -> part.charAt(0) + part.substring(1).toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    public TenantSubscription getMySubscription() {
        String currentTenant = TenantContext.getTenant();
        if (currentTenant == null || currentTenant.equals("MASTER")) {
            throw new BadRequestException("No active shop context found!");
        }
        return TenantContext.callWith("MASTER", () -> newMasterTx().execute(status ->
                tenantSubscriptionRepository.findByTenantId(currentTenant)
                        .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for this shop."))));
    }

    private TransactionTemplate newMasterTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return tx;
    }
}
