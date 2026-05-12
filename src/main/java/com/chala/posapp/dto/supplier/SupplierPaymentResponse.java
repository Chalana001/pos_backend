package com.chala.posapp.dto.supplier;

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
    private String note;
    private LocalDateTime paidAt;
}
