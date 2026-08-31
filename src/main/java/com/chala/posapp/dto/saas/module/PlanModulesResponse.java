package com.chala.posapp.dto.saas.module;

import java.util.List;

/**
 * A plan's module template, plus how many live shops the template governs — editing a plan
 * changes every shop on it that has no override, so the panel warns with this number.
 */
public record PlanModulesResponse(
        Long planId,
        String planName,
        long shopsOnPlan,
        int enabledCount,
        int totalCount,
        List<ModuleNodeResponse> modules
) {
}
