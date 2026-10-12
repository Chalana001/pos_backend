package com.chala.posapp.dto.shift;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Per-payment-method sales summary for one shift, the authoritative numbers the
 * Close Shift dialog renders. Every row comes from the order_payments ledger
 * grouped over the shift's window (branch + cashier + time), so counts and
 * totals match the money actually recorded; the configured payment_methods row
 * (if any) supplies the display name, category and icon key. Rows are methods,
 * never groups: category grouping is the frontend's presentation of these rows.
 */
@Getter
@Builder
public class ShiftPaymentSummaryResponse {

    private Long shiftId;

    /** Total completed-sale amount across all methods (sum of rows). */
    private double totalSales;

    /** Count of completed sales across all methods (sum of rows). */
    private long totalTransactions;

    /** One row per payment method code found in this shift's payments. */
    private List<MethodRow> methods;

    /** Cash-drawer money collected on old credit dues (display-only, never in expected cash). */
    private double cashCollections;

    @Getter
    @Builder
    public static class MethodRow {
        /** The payment method code exactly as recorded on order_payments. */
        private String code;
        /** Configured display name, or the humanized code for unknown/legacy methods. */
        private String displayName;
        /** Configured category, or a best-effort category for unknown codes. */
        private String category;
        private String iconKey;
        /** Configured reference label (e.g. "Bank Reference") for drill-down rows. */
        private String referenceLabel;
        private long transactionCount;
        private double amount;
        /** amount / totalSales, 0 when there are no sales. */
        private double percentage;
        /** Whether this method's tender counts toward physical drawer cash. */
        private boolean includeInExpectedCash;
        /** True when the code no longer resolves to a configured payment method. */
        private boolean unknown;
        /** True when the method resolves but is deactivated (historical sales remain). */
        private boolean inactive;
    }
}
