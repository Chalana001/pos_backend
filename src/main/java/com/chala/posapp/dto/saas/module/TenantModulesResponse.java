package com.chala.posapp.dto.saas.module;

import java.util.List;

/**
 * The full module picture for one shop: the catalog tree with this shop's effective values,
 * plus a count of how far it has drifted from its plan.
 */
public record TenantModulesResponse(
        String tenantId,
        String shopName,
        Long planId,
        String planName,
        String businessType,
        int enabledCount,
        int totalCount,
        int overrideCount,
        List<ModuleNodeResponse> modules
) {
}
