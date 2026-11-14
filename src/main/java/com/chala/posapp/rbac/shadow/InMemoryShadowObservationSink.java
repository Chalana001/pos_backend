package com.chala.posapp.rbac.shadow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Bounded process-local sink for tests and local shadow diagnostics; no audit-table writes. */
@Component
public class InMemoryShadowObservationSink implements ShadowObservationSink {
    private static final int MAX_ENTRIES = 2_000;
    private final CopyOnWriteArrayList<ShadowObservation> observations = new CopyOnWriteArrayList<>();

    @Override
    public void record(ShadowObservation observation) {
        if (observation == null) return;
        observations.add(observation);
        while (observations.size() > MAX_ENTRIES) {
            observations.remove(0);
        }
    }

    @Override
    public List<ShadowObservation> snapshot() {
        return List.copyOf(observations);
    }

    public void clear() {
        observations.clear();
    }
}
