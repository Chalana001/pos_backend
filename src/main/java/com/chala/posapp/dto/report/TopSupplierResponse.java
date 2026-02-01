package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class TopSupplierResponse {
    private Long supplierId;
    private String supplierName;
    private String contactNo;
    private long purchaseCount;
    private double totalPurchased;
}
