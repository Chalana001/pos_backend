package com.chala.posapp.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class DateRangeUtils {

    private DateRangeUtils() {
    }

    public static DateTimeRange fullDayRange(LocalDate from, LocalDate to) {
        return new DateTimeRange(
                from.atStartOfDay(),
                to.atTime(23, 59, 59)
        );
    }

    public record DateTimeRange(LocalDateTime from, LocalDateTime to) {
    }
}
