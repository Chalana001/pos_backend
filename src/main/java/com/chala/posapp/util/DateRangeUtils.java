package com.chala.posapp.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class DateRangeUtils {

    private static final LocalDate DEFAULT_FROM = LocalDate.of(1970, 1, 1);

    private DateRangeUtils() {
    }

    public static DateTimeRange fullDayRange(LocalDate from, LocalDate to) {
        LocalDate effectiveFrom = from == null ? DEFAULT_FROM : from;
        LocalDate effectiveTo = to == null ? LocalDate.now() : to;
        return new DateTimeRange(
                effectiveFrom.atStartOfDay(),
                effectiveTo.atTime(23, 59, 59)
        );
    }

    public record DateTimeRange(LocalDateTime from, LocalDateTime to) {
    }
}
