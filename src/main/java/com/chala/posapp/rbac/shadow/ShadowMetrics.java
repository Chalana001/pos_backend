package com.chala.posapp.rbac.shadow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ShadowMetrics {
    private final Counter evaluations;
    private final Counter matches;
    private final Counter mismatches;
    private final Counter unobserved;
    private final Counter errors;
    private final Counter selectedGrants;
    private final Counter deniedEvents;
    private final Counter filteredGrants;
    private final Counter multipleEventRequests;
    private final Timer evaluationDuration;

    public ShadowMetrics(MeterRegistry registry) {
        evaluations = Counter.builder("pos.rbac.shadow.evaluations").register(registry);
        matches = Counter.builder("pos.rbac.shadow.matches").register(registry);
        mismatches = Counter.builder("pos.rbac.shadow.mismatches").register(registry);
        unobserved = Counter.builder("pos.rbac.shadow.unobserved").register(registry);
        errors = Counter.builder("pos.rbac.shadow.errors").register(registry);
        selectedGrants = Counter.builder("pos.rbac.shadow.selected_grants").register(registry);
        deniedEvents = Counter.builder("pos.rbac.shadow.denied_events").register(registry);
        filteredGrants = Counter.builder("pos.rbac.shadow.filtered_grants").register(registry);
        multipleEventRequests = Counter.builder("pos.rbac.shadow.multiple_event_requests").register(registry);
        evaluationDuration = Timer.builder("pos.rbac.shadow.evaluation.duration").register(registry);
    }

    public void evaluation(ShadowEvaluationResult result) {
        try {
            evaluations.increment();
            evaluationDuration.record(result.duration());
            switch (result.status()) {
                case ERROR -> errors.increment();
                case UNOBSERVED, UNMAPPED, NOT_READY -> unobserved.increment();
                default -> { }
            }
        } catch (RuntimeException ignored) {
        }
    }

    public void comparison(ComparisonStatus status) {
        try {
            if (status == ComparisonStatus.MATCH) matches.increment();
            if (status == ComparisonStatus.MISMATCH) mismatches.increment();
            if (status == ComparisonStatus.UNOBSERVED) unobserved.increment();
        } catch (RuntimeException ignored) {
        }
    }

    public void selectedGrant() { incrementSafely(selectedGrants); }
    public void deniedEvent() { incrementSafely(deniedEvents); }
    public void filteredGrant() { incrementSafely(filteredGrants); }
    public void multipleEventRequest() { incrementSafely(multipleEventRequests); }

    private void incrementSafely(Counter counter) {
        try { counter.increment(); } catch (RuntimeException ignored) { }
    }
}
