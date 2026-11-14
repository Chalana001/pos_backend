package com.chala.posapp.rbac.shadow;

import java.util.List;

public interface ShadowObservationSink {
    void record(ShadowObservation observation);

    default List<ShadowObservation> snapshot() {
        return List.of();
    }
}
