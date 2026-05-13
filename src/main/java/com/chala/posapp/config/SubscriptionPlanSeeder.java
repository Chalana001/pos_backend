package com.chala.posapp.config;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPlanSeeder implements CommandLineRunner {

    private final SubscriptionPlanRepository planRepository;
    private final com.chala.posapp.repository.TenantSubscriptionRepository tenantSubscriptionRepository;

    private static final Set<String> LEGACY_PLAN_NAMES = Set.of(
            "MONTHLY_DEMO",
            "MONTHLY_LITE",
            "MONTHLY_BASIC",
            "MONTHLY_PRO",
            "YEARLY_LITE",
            "YEARLY_PRO",
            "LIFETIME_YEARLY"
    );

    @Override
    public void run(String... args) {
        log.info("Syncing subscription plans...");

        upsertPlan("FREE", "MONTHLY_DEMO", BillingCycle.MONTHLY, 0.0, 0.0, 1);
        upsertPlan("STANDARD", "MONTHLY_LITE", BillingCycle.MONTHLY, 1500.0, 1500.0, 1);
        upsertPlan("PRO", "MONTHLY_PRO", BillingCycle.MONTHLY, 2500.0, 2500.0, 3);
        removeUnusedLegacyPlans();

        log.info("Subscription plans synced: FREE, STANDARD, PRO");
    }

    private void upsertPlan(String targetName, String legacyName, BillingCycle billingCycle,
                            double initialPrice, double renewalPrice, int maxBranches) {
        Optional<SubscriptionPlan> existing = planRepository.findByName(targetName);
        if (existing.isEmpty() && legacyName != null && !legacyName.isBlank()) {
            existing = planRepository.findByName(legacyName);
        }

        SubscriptionPlan plan = existing.orElseGet(SubscriptionPlan::new);
        plan.setName(targetName);
        plan.setBillingCycle(billingCycle);
        plan.setInitialPrice(initialPrice);
        plan.setRenewalPrice(renewalPrice);
        plan.setMaxBranches(maxBranches);
        planRepository.save(plan);
    }

    private void removeUnusedLegacyPlans() {
        List<SubscriptionPlan> legacyPlans = planRepository.findByNameIn(LEGACY_PLAN_NAMES.stream().toList());
        legacyPlans.stream()
                .filter(plan -> !tenantSubscriptionRepository.existsByPlanId(plan.getId()))
                .forEach(planRepository::delete);
    }
}
