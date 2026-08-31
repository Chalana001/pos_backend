package com.chala.posapp.util;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reason the report-export queue stamps its due-times through
 * {@link StorageClock} instead of {@link LocalDateTime#now()}.
 *
 * <p>The databases round a timestamp that is more precise than its column, so a
 * raw nanosecond clock read lands in the table slightly <em>after</em> the instant
 * it was taken. A queue poll in that same window then reads
 * {@code next_attempt_at <= now} as false and skips a job that was already due.
 */
class StorageClockTest {

    @Test
    void nowIsTruncatedToTheColumnPrecision() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertEquals(0, StorageClock.now().getNano() % 1_000_000,
                    "StorageClock.now() must not carry sub-millisecond precision");
        }
    }

    @Test
    void toStorageDropsSubMillisecondPrecisionAndPassesNullThrough() {
        LocalDateTime precise = LocalDateTime.parse("2026-08-26T21:07:11.011199600");
        assertEquals(LocalDateTime.parse("2026-08-26T21:07:11.011"), StorageClock.toStorage(precise));
        assertNull(StorageClock.toStorage(null));
    }

    @Test
    void rawNanosecondStampsCanBeStoredAheadOfTheInstantTheyWereTaken() throws Exception {
        // The defect, reproduced against the database rather than argued about: a value
        // whose sub-millisecond remainder rounds up comes back later than it went in, and
        // the due-query then misses it.
        LocalDateTime precise = LocalDateTime.parse("2026-08-26T21:07:11.011199600");

        try (Connection connection = h2()) {
            assertTrue(roundTrip(connection, precise).isAfter(precise),
                    "expected the database to round a sub-millisecond stamp forward");
            assertFalse(dueAt(connection, precise), "a stamp stored in the future is skipped by 'next_attempt_at <= now'");
        }
    }

    @Test
    void storageClockStampsSurviveTheRoundTripAndStayDue() throws Exception {
        try (Connection connection = h2()) {
            for (int attempt = 0; attempt < 200; attempt++) {
                LocalDateTime stamped = StorageClock.now();
                assertEquals(stamped, roundTrip(connection, stamped), "stored value drifted from the stamped value");
                assertTrue(dueAt(connection, stamped), "a job stamped now must be due now");
            }
        }
    }

    private Connection h2() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:storage-clock-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        connection.createStatement().execute("create table jobs (next_attempt_at timestamp(6))");
        return connection;
    }

    private LocalDateTime roundTrip(Connection connection, LocalDateTime value) throws Exception {
        connection.createStatement().execute("delete from jobs");
        try (PreparedStatement insert = connection.prepareStatement("insert into jobs values (?)")) {
            insert.setObject(1, value);
            insert.executeUpdate();
        }
        try (ResultSet stored = connection.createStatement().executeQuery("select next_attempt_at from jobs")) {
            stored.next();
            return stored.getObject(1, LocalDateTime.class);
        }
    }

    private boolean dueAt(Connection connection, LocalDateTime now) throws Exception {
        try (PreparedStatement due = connection.prepareStatement("select 1 from jobs where next_attempt_at <= ?")) {
            due.setObject(1, now);
            try (ResultSet rows = due.executeQuery()) {
                return rows.next();
            }
        }
    }
}
