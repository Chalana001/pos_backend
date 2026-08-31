package com.chala.posapp.dto.report;

import java.time.LocalDateTime;
import java.util.List;

public record ForecastAccuracyResponse(
        int evaluatedSnapshots,
        int maturingSnapshots,
        Double portfolioWape,
        List<SnapshotAccuracy> history
) {
    public record SnapshotAccuracy(Long id, Long exportJobId, Long branchId, int forecastDays,
                                   LocalDateTime windowStart, LocalDateTime windowEnd,
                                   LocalDateTime evaluatedAt, int scoredItems, int totalItems,
                                   double predictedQty, double actualQty, double absoluteError,
                                   Double wape) {}
}
