package com.chala.posapp.dto.saas;

import java.util.List;

/**
 * A plan as the POS app's packages page shows it to a shop owner.
 *
 * <p>Carries the module keys the plan includes, which is what lets the page build a real
 * comparison instead of a hand-written feature list — and means the page can never claim
 * something the server does not actually grant.
 *
 * <p>Field names deliberately match the entity this replaced on {@code /api/saas/plans}, so
 * anything already reading {@code name} or {@code initialPrice} keeps working.
 */
public record PublicPlanResponse(
        Long id,
        String name,
        String label,
        String billingCycle,
        double initialPrice,
        double renewalPrice,
        int maxBranches,
        String description,
        String color,
        int trialDays,
        /** Module keys this plan switches on, parents already applied. */
        List<String> moduleKeys,
        int enabledModuleCount,
        int totalModuleCount
) {
}
