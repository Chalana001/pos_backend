package com.chala.posapp.dto;

import com.chala.posapp.dto.grn.GrnResponse;
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
    private BigDecimal dueAmount;
    private PurchaseStatus status;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime canceledAt;
    private List<GrnResponse> grnList;
}
