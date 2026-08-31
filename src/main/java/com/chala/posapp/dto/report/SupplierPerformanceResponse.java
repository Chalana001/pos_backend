package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPerformanceResponse {
    private Long supplierId;
    private String supplierName;
    private String contactNo;
    private long purchaseCount;
    private double totalPurchased;
    private double totalPaid;
    private double totalDue;
    private double averagePurchaseValue;
    private LocalDateTime lastPurchaseAt;
}
