package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.PurchaseStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPurchaseHistoryResponse {
    private Long purchaseId;
    private String invoiceNo;
    private String supplierName;
    private Long branchId;
    private String branchName;
    private String grnNo;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal lineTotal;
    private LocalDateTime receivedAt;
    private PurchaseStatus status;
}
