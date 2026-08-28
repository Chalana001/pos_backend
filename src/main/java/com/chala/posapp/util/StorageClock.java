package com.chala.posapp.util;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Clock reads for timestamps that get written to the database and later compared
 * against another clock read (queue due-times, schedule next-run times).
 *
 * <p>{@link LocalDateTime#now()} carries nanoseconds. The columns behind these
 * timestamps are {@code DATETIME(3)} on MySQL, and both MySQL and H2 <em>round</em>
 * a value that is more precise than the column instead of truncating it. A job
 * stamped {@code 12:00:00.011199600} therefore lands in the table as
 * {@code 12:00:00.011} on MySQL and {@code 12:00:00.011200} on H2 — up to half a
 * millisecond <em>after</em> the instant the worker actually stamped it. A poll
 * taken in that same window then evaluates {@code next_attempt_at <= now} as false
 * and silently skips a job that was due, because the stored value is in the future.
 *
 * <p>Truncating the read to the column's own precision keeps the stored value equal
 * to the value in memory, so the comparison stays exact.
 */
public final class StorageClock {

    /** Precision of the DATETIME(3) columns these timestamps are stored in. */
    private static final ChronoUnit COLUMN_PRECISION = ChronoUnit.MILLIS;

    private StorageClock() {
    }

    /** Current time, truncated to the precision the database column can hold. */
    public static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(COLUMN_PRECISION);
    }

    /** Truncate an externally supplied timestamp to the precision of its column. */
    public static LocalDateTime toStorage(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(COLUMN_PRECISION);
    }
}
