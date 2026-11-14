package com.chala.posapp.rbac.shadow;

import java.time.Duration;

/** One method-security event or one final request outcome; never aggregate across layers. */
public record ShadowObservation(
        String correlationId,
        long sequence,
        String tenantId,
        Long userId,
        String legacyRole,
        SecuredMethodIdentity method,
        String routeTemplate,
        String permissionKey,
        String legacyDecision,
        ShadowEvaluationResult shadow,
        ComparisonStatus comparison,
        int httpStatus,
        boolean methodSecurityEvent,
        Duration duration) {
    public ShadowObservation {
        duration = duration == null ? Duration.ZERO : duration;
    }
}
