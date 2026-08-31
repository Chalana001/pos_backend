package com.chala.posapp.dto.saas;

/**
 * A plan as the panel's plan cards render it.
 *
 * @param shopCount          how many shops are on this plan — the delete guard and the
 *                           "editing this affects N shops" warning both use it
 * @param enabledModuleCount how many of the catalog's modules this plan's template switches on
 * @param monthlyValue       renewal price normalised to a month, so yearly and monthly plans
 *                           can be compared and summed into MRR
 */
public record PlanResponse(
        Long id,
        String name,
        String billingCycle,
        double initialPrice,
        double renewalPrice,
        double monthlyValue,
        int maxBranches,
        String description,
        String color,
        int displayOrder,
        boolean active,
        boolean systemPlan,
        long shopCount,
        int enabledModuleCount,
        int totalModuleCount
) {
}
