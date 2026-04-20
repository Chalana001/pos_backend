package com.chala.posapp.service;

import com.chala.posapp.entity.SubscriptionPlan;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.SubscriptionPlanRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;

    @Transactional(readOnly = true)
    public List<SubscriptionPlan> getAllPlans() {
        Map<String, Integer> displayOrder = Map.of(
                "MONTHLY_LITE", 1,
                "YEARLY_LITE", 2,
                "MONTHLY_PRO", 3,
                "YEARLY_PRO", 4
        );

        return planRepository.findAll().stream()
                .filter(plan -> displayOrder.containsKey(plan.getName()))
                .sorted(Comparator.comparingInt(plan -> displayOrder.get(plan.getName())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantSubscription getMySubscription() {
        String currentTenant = TenantContext.getTenant();

        if (currentTenant == null || currentTenant.equals("MASTER")) {
            throw new BadRequestException("No active shop context found!");
        }

        return tenantSubscriptionRepository.findByTenantId(currentTenant)
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found for this shop."));
    }
}
