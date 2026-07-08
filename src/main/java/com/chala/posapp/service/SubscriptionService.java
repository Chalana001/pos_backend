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
    private final PlatformTransactionManager transactionManager;

    public List<SubscriptionPlan> getAllPlans() {
        Map<String, Integer> displayOrder = Map.of(
                "FREE", 1,
                "STANDARD", 2,
                "PRO", 3
        );
        return TenantContext.callWith("MASTER", () -> newMasterTx().execute(status ->
                planRepository.findAll().stream()
                        .filter(plan -> displayOrder.containsKey(plan.getName()))
                        .sorted(Comparator.comparingInt(plan -> displayOrder.get(plan.getName())))
                        .toList()));
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
