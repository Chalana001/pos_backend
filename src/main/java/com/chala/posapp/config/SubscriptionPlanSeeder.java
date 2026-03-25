package com.chala.posapp.config;

import com.chala.posapp.entity.BillingCycle;
import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPlanSeeder implements CommandLineRunner {

    private final SubscriptionPlanRepository planRepository;

    @Override
    public void run(String... args) throws Exception {

        if (planRepository.count() == 0) {
            log.info("Seeding Subscription Plans...");

            SubscriptionPlan monthlyBasic = SubscriptionPlan.builder()
                    .name("MONTHLY_BASIC")
                    .billingCycle(BillingCycle.MONTHLY)
                    .initialPrice(2000.0)
                    .renewalPrice(2000.0)
                    .maxBranches(1)
                    .build();

            SubscriptionPlan monthlyPro = SubscriptionPlan.builder()
                    .name("MONTHLY_PRO")
                    .billingCycle(BillingCycle.MONTHLY)
                    .initialPrice(2500.0)
                    .renewalPrice(2500.0)
                    .maxBranches(2)
                    .build();

            SubscriptionPlan lifetimeYearly = SubscriptionPlan.builder()
                    .name("LIFETIME_YEARLY")
                    .billingCycle(BillingCycle.YEARLY)
                    .initialPrice(40000.0)
                    .renewalPrice(10000.0)
                    .maxBranches(1)
                    .build();

            planRepository.saveAll(Arrays.asList(monthlyBasic, monthlyPro, lifetimeYearly));

            log.info("✅ Subscription Plans auto-added successfully!");
        } else {
            log.info("👍 Subscription Plans are already in the database. Skipping seeder.");
        }
    }
}