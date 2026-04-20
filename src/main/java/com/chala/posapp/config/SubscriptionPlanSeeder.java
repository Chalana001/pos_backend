package com.chala.posapp.config;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPlanSeeder implements CommandLineRunner {

    private final SubscriptionPlanRepository planRepository;

    @Override
    public void run(String... args) {
        log.info("Syncing subscription plans...");

        upsertPlan("MONTHLY_DEMO", null, BillingCycle.MONTHLY, 0.0, 0.0, 2);
        upsertPlan("MONTHLY_LITE", "MONTHLY_BASIC", BillingCycle.MONTHLY, 1500.0, 1500.0, 1);
        upsertPlan("MONTHLY_PRO", null, BillingCycle.MONTHLY, 2500.0, 2500.0, 2);
        upsertPlan("YEARLY_LITE", null, BillingCycle.YEARLY, 25000.0, 10000.0, 1);
        upsertPlan("YEARLY_PRO", "LIFETIME_YEARLY", BillingCycle.YEARLY, 35000.0, 10000.0, 2);

        log.info("Subscription plans synced: MONTHLY_DEMO, MONTHLY_LITE, YEARLY_LITE, MONTHLY_PRO, YEARLY_PRO");
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
}
