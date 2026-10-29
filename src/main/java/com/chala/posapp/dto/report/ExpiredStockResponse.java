package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Batch-level expired / expiring stock report. One row per positive-quantity
 * stock batch (zero-quantity rows only when includeZeroQuantity is requested),
 * so summary totals never double-count an item that has several batches.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpiredStockResponse {

    private int expiryAlertMonths;
    private LocalDate today;
    private LocalDate cutoffDate;
    private Summary summary;
    private List<BatchRow> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private long affectedItems;
        private long expiredBatches;
        private long expiredQuantity;
        private BigDecimal expiredCostValue;
        private long upcomingBatches;
        private long upcomingQuantity;
        private BigDecimal upcomingCostValue;
        private long missingExpiryBatches;
        private long missingExpiryQuantity;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchRow {
        private Long batchId;
        private String batchCode;
        private Long itemId;
        private String barcode;
        private String itemName;
        private String altName;
        private String unit;
        private Long branchId;
        private String branchName;
        private String supplierName;
        private String grnNo;
        private LocalDateTime expireDate;
        private Long daysPastExpiry;
        private Long daysToExpiry;
        private Integer quantity;
        // Human-unit quantity (milli-unit scaling already applied): what the UI
        // shows and what estimatedCostValue multiplies cost by.
        private double displayQuantity;
        private BigDecimal costPrice;
        private BigDecimal estimatedCostValue;
        private String expiryStatus;
        private String sourceType;
        private LocalDateTime receivedAt;
    }
}
