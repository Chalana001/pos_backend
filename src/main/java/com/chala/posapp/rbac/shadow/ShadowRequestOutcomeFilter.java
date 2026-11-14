package com.chala.posapp.rbac.shadow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Outer request observer; it records the response separately from method-security events. */
@Component
public class ShadowRequestOutcomeFilter extends OncePerRequestFilter {
    private final ShadowObservationSink sink;
    private final ShadowMetrics metrics;
    private final boolean enabled;

    public ShadowRequestOutcomeFilter(ShadowObservationSink sink, ShadowMetrics metrics,
                                      @Value("${app.rbac.shadow-enabled:false}") boolean enabled) {
        this.sink = sink;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        Instant started = Instant.now();
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "shadow-" + started.toEpochMilli() + "-" + System.identityHashCode(request);
        }
        request.setAttribute(ShadowRequestAttributes.CORRELATION_ID, correlationId);
        request.setAttribute(ShadowRequestAttributes.EVENT_SEQUENCE, new AtomicLong());
        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object outcome = request.getAttribute(ShadowRequestAttributes.OUTCOME_CLASS);
            String outcomeClass = outcome == null ? "UNOBSERVED" : outcome.toString();
            ShadowClassification classification = switch (outcomeClass) {
                case "TENANT_BOUNDARY" -> ShadowClassification.TENANT_BOUNDARY;
                case "MODULE_OR_SUBSCRIPTION_CONSTRAINT" -> ShadowClassification.MODULE_OR_SUBSCRIPTION_CONSTRAINT;
                case "AUTHENTICATION" -> ShadowClassification.LEGACY_ONLY;
                default -> ShadowClassification.LEGACY_ONLY;
            };
            ShadowReason reason = switch (outcomeClass) {
                case "TENANT_BOUNDARY" -> ShadowReason.TENANT_RBAC_NOT_READY;
                case "MODULE_OR_SUBSCRIPTION_CONSTRAINT" -> ShadowReason.BUSINESS_CONSTRAINT_NOT_EVALUATED;
                case "AUTHENTICATION" -> ShadowReason.NO_ROLE_ASSIGNMENT;
                default -> ShadowReason.BUSINESS_CONSTRAINT_NOT_EVALUATED;
            };
            ShadowEvaluationResult shadow = ShadowEvaluationResult.notApplicable(null, classification, reason);
            metrics.comparison(ComparisonStatus.NOT_APPLICABLE);
            sink.record(new ShadowObservation(correlationId, 0, request.getAttribute("tenantId") instanceof String t ? t : null,
                    null, authentication == null ? null : authentication.getAuthorities().stream().findFirst().map(a -> a.getAuthority()).orElse(null),
                    null, null, null, outcomeClass, shadow, ComparisonStatus.NOT_APPLICABLE,
                    response.getStatus(), false, Duration.between(started, Instant.now())));
        }
    }
}
