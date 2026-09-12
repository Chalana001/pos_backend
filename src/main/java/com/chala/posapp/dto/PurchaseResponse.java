package com.chala.posapp.dto;

import com.chala.posapp.dto.grn.GrnResponse;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.PurchaseStatus;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PurchaseResponse {
    private Long purchaseId;
    private String invoiceNo;
    private Long supplierId;
    private String supplierName;
    private BigDecimal grandTotal;
    private BigDecimal discountAmount;
    private BigDecimal paidAmount;
    private String paymentMethod;
    private CashSource cashSource;
    private Long cashShiftId;
    private Long cashierUserId;
    private BigDecimal cashSourceAmount;
    private Long cashSourceBranchId;
    private BigDecimal dueAmount;
    private PurchaseStatus status;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime canceledAt;
    private String canceledByUsername;
    private List<GrnResponse> grnList;

    // Return summary — populated on detail fetch
    private boolean hasReturns;
    private int returnCount;
    private BigDecimal totalReturnedAmount;

    // Supersede chain. replacesPurchaseId points back at the bill this one corrected;
    // replacedByPurchaseId points forward at the bill that corrected this one.
    private Long replacesPurchaseId;
    private String replacesInvoiceNo;
    private Long replacedByPurchaseId;
    private String replacedByInvoiceNo;

    /**
     * Whether "Cancel & Rebuild" can run on this bill right now, and if not, why.
     *
     * Answered by the server because the reasons are all server-side state — whether any of
     * the bill's stock has moved, whether its drawer shift is still open, whether payments
     * have been allocated to it. Without this the screen has to guess, and the operator
     * finds out the bill cannot be voided only after re-typing forty lines.
     */
    private boolean canReplace;
    private String replaceBlockedReason;
}
