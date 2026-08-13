package com.chala.posapp.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReportExportMetrics {
    private final Map<String, Counter> counters;

    public ReportExportMetrics(MeterRegistry registry) {
        this.counters = Map.of(
                "completed", counter(registry, "completed"),
                "failed", counter(registry, "failed"),
                "automatic_retry", counter(registry, "automatic_retry"),
                "manual_retry", counter(registry, "manual_retry"),
                "recovered", counter(registry, "recovered"),
                "cancelled", counter(registry, "cancelled"),
                "deleted", counter(registry, "deleted"),
                "retention_deleted", counter(registry, "retention_deleted"),
                "emailed", counter(registry, "emailed")
        );
    }

    public void increment(String event) {
        Counter counter = counters.get(event);
        if (counter == null) throw new IllegalArgumentException("Unknown report export metric event: " + event);
        counter.increment();
    }

    private Counter counter(MeterRegistry registry, String event) {
        return Counter.builder("pos.report.exports")
                .description("Report export lifecycle events")
                .tag("event", event)
                .register(registry)
                ;
    }
}
