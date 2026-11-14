package com.chala.posapp.rbac.shadow;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.authorization.event.AuthorizationGrantedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Records each method-security event independently; it never aggregates a request. */
@Component
public class AuthorizationShadowEventListener {
    private final TenantRbacShadowEvaluator evaluator;
    private final ShadowObservationSink sink;
    private final ShadowMetrics metrics;
    private final AtomicLong sequence = new AtomicLong();

    public AuthorizationShadowEventListener(TenantRbacShadowEvaluator evaluator, ShadowObservationSink sink,
                                             ShadowMetrics metrics) {
        this.evaluator = evaluator;
        this.sink = sink;
        this.metrics = metrics;
    }

    @EventListener
    public void onGranted(AuthorizationGrantedEvent<?> event) {
        observe(event.getObject(), true);
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        observe(event.getObject(), false);
    }

    private long nextSequence(ServletRequestAttributes attributes) {
        Object value = attributes.getRequest().getAttribute(ShadowRequestAttributes.EVENT_SEQUENCE);
        if (value instanceof AtomicLong counter) return counter.incrementAndGet();
        return sequence.incrementAndGet();
    }

    private void observe(Object securedObject, boolean legacyAllowed) {
        if (!(securedObject instanceof MethodInvocation invocation)) {
            return;
        }
        Method method = invocation.getMethod();
        LegacyPermissionMappingCatalog.find(SecuredMethodIdentity.of(method))
                .filter(mapping -> mapping.classification() == ShadowClassification.MAPPED_CAPABILITY)
                .ifPresent(mapping -> {
                    ShadowEvaluationResult shadow = evaluator.evaluate(mapping.permissionKey());
                    metrics.evaluation(shadow);
                    if (legacyAllowed) metrics.selectedGrant(); else metrics.deniedEvent();
                    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    String correlationId = attributes == null ? "method-event-" + sequence.incrementAndGet()
                            : String.valueOf(attributes.getRequest().getAttribute(ShadowRequestAttributes.CORRELATION_ID));
                    long eventSequence = attributes == null ? sequence.incrementAndGet() : nextSequence(attributes);
                    ComparisonStatus comparison = shadow.status() == ShadowEvaluationStatus.EVALUATED
                            ? ((legacyAllowed && shadow.decision() == ShadowDecision.ALLOW)
                            || (!legacyAllowed && shadow.decision() == ShadowDecision.DENY)
                            ? ComparisonStatus.MATCH : ComparisonStatus.MISMATCH)
                            : ComparisonStatus.UNOBSERVED;
                    metrics.comparison(comparison);
                    if (eventSequence > 1) metrics.multipleEventRequest();
                    sink.record(new ShadowObservation(correlationId, eventSequence, null, shadow.userId(),
                            shadow.legacyRole() == null ? null : shadow.legacyRole().name(),
                            SecuredMethodIdentity.of(method), null, mapping.permissionKey(),
                            legacyAllowed ? "ALLOW" : "DENY", shadow, comparison, 0, true, shadow.duration()));
                });
    }
}
