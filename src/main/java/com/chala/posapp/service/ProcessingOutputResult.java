package com.chala.posapp.service;

import com.chala.posapp.dto.item.ProcessingOutputValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable request-local snapshot of processing outputs grouped by source
 * item id. Lists retain the legacy {@code id ASC} relationship order; source
 * id ordering is an implementation detail and is not part of the response
 * contract. No JPA entities or mutable collections escape this result.
 */
public final class ProcessingOutputResult {

    private static final ProcessingOutputResult EMPTY =
            new ProcessingOutputResult(Collections.emptyMap());

    private final Map<Long, List<ProcessingOutputValue>> valuesBySource;

    private ProcessingOutputResult(Map<Long, List<ProcessingOutputValue>> valuesBySource) {
        this.valuesBySource = valuesBySource;
    }

    public static ProcessingOutputResult empty() {
        return EMPTY;
    }

    /** Defensively copies every group and exposes an unmodifiable map. */
    public static ProcessingOutputResult from(Map<Long, List<ProcessingOutputValue>> grouped) {
        Map<Long, List<ProcessingOutputValue>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ProcessingOutputValue>> entry : grouped.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return new ProcessingOutputResult(Collections.unmodifiableMap(snapshot));
    }

    /** Empty immutable list for an unknown or rowless source item. */
    public List<ProcessingOutputValue> outputsFor(Long sourceItemId) {
        return valuesBySource.getOrDefault(sourceItemId, List.of());
    }

    public Map<Long, List<ProcessingOutputValue>> valuesBySource() {
        return valuesBySource;
    }

    public boolean isEmpty() {
        return valuesBySource.isEmpty();
    }
}
