package com.chala.posapp.dto.supplier;

import com.chala.posapp.entity.CashSource;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class SupplierPaymentResponse {
    private Long id;
    private Long supplierId;
    private String supplierName;
    private Long purchaseId;
    private String invoiceNo;
    private BigDecimal amount;
    private String paymentMethod;
    private CashSource cashSource;
    private Long cashShiftId;
    private Long cashierUserId;
    private Long cashSourceBranchId;
    private String note;
    private LocalDateTime paidAt;
}
