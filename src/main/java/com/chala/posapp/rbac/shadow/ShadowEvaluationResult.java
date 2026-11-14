package com.chala.posapp.rbac.shadow;

import com.chala.posapp.entity.Role;

import java.time.Duration;

public record ShadowEvaluationResult(
        String permissionKey,
        ShadowDecision decision,
        ShadowEvaluationStatus status,
        ShadowClassification classification,
        ShadowReason reason,
        Long userId,
        Long roleId,
        Long rbacVersion,
        Role legacyRole,
        String identitySource,
        Duration duration) {
    public ShadowEvaluationResult {
        duration = duration == null ? Duration.ZERO : duration;
    }

    public static ShadowEvaluationResult notApplicable(String permissionKey,
                                                       ShadowClassification classification,
                                                       ShadowReason reason) {
        return new ShadowEvaluationResult(permissionKey, ShadowDecision.NOT_APPLICABLE,
                ShadowEvaluationStatus.NOT_APPLICABLE, classification, reason,
                null, null, null, null, null, Duration.ZERO);
    }
}
