package com.chala.posapp.dto;

import com.chala.posapp.entity.PurchaseStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact view of a Purchase for the shift close/reconciliation screens.
 *
 * Deliberately lighter than {@link PurchaseResponse} (no GRN list, no return
 * summary) — this is for "which purchases ate into this shift's cash",
 * not full purchase detail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftPurchaseSummaryResponse {
    private Long purchaseId;
    private String invoiceNo;
    private String supplierName;

    // What actually left the shift's cash drawer for this purchase — not
    // grandTotal, since a purchase can be partially paid from the drawer and
    // partially on credit/bank.
    private BigDecimal cashSourceAmount;

    private PurchaseStatus status;
    private LocalDateTime createdAt;
}
