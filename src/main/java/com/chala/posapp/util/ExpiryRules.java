package com.chala.posapp.util;

import java.time.LocalDate;

/**
 * Single source of truth for expiry status boundaries, shared by the Stock
 * Inventory expiry filter and the expired-stock report so the two can never
 * disagree about what EXPIRED, EXPIRING_SOON or NOT_EXPIRED means.
 *
 * Boundaries (inclusive where stated):
 *   expireDate &lt; today            → EXPIRED
 *   today ≤ expireDate ≤ cutoff    → EXPIRING_SOON (a batch expiring today is
 *                                     expiring, not expired)
 *   expireDate &gt; cutoff            → NOT_EXPIRED
 *   expireDate IS NULL             → MISSING_EXPIRY (never silently safe)
 *
 * The cutoff uses calendar-month arithmetic (LocalDate.plusMonths), not a
 * fixed 30-day approximation. Zero-quantity batches never contribute to
 * expired/soon totals; callers must keep that exclusion in their queries.
 */
public final class ExpiryRules {

    public static final int DEFAULT_ALERT_MONTHS = 2;
    public static final int MAX_ALERT_MONTHS = 12;
    public static final String STATUS_ALL = "ALL";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_EXPIRING_SOON = "EXPIRING_SOON";
    public static final String STATUS_NOT_EXPIRED = "NOT_EXPIRED";
    public static final String STATUS_MISSING_EXPIRY = "MISSING_EXPIRY";

    private ExpiryRules() {
    }

    /**
     * Normalizes the alert-months setting: null or non-positive falls back to
     * the default, values above the cap clamp to it so a stale stored value
     * cannot push the window out absurdly far.
     */
    public static int normalizeAlertMonths(Integer configured) {
        if (configured == null || configured <= 0) return DEFAULT_ALERT_MONTHS;
        return Math.min(configured, MAX_ALERT_MONTHS);
    }

    /** Cutoff date (inclusive) for EXPIRING_SOON, from the alert-months setting. */
    public static LocalDate cutoffDate(int alertMonths, LocalDate today) {
        return today.plusMonths(normalizeAlertMonths(alertMonths));
    }

    /** Status of a single batch relative to today and the cutoff. Null date → MISSING_EXPIRY. */
    public static String statusOf(LocalDate expireDate, LocalDate today, int alertMonths) {
        if (expireDate == null) return STATUS_MISSING_EXPIRY;
        if (expireDate.isBefore(today)) return STATUS_EXPIRED;
        if (!expireDate.isAfter(cutoffDate(alertMonths, today))) return STATUS_EXPIRING_SOON;
        return STATUS_NOT_EXPIRED;
    }

    /** Normalizes a client-supplied expiry status filter; blank/unknown → ALL. */
    public static String normalizeFilter(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        return switch (value) {
            case STATUS_EXPIRED, STATUS_EXPIRING_SOON, STATUS_NOT_EXPIRED, STATUS_MISSING_EXPIRY -> value;
            default -> STATUS_ALL;
        };
    }
}
