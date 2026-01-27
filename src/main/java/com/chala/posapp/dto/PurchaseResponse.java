package com.chala.posapp.dto;

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
    private String supplierName;
    private BigDecimal grandTotal;
    private LocalDateTime createdAt;
    private List<GrnResponse> grnList;
}