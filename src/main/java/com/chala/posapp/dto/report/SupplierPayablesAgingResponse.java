package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPayablesAgingResponse {
    private Long supplierId;
    private String supplierName;
    private String contactNo;
    private double totalDue;
    private double bucket0to30;
    private double bucket31to60;
    private double bucket61to90;
    private double bucket91plus;
    private LocalDateTime oldestPurchaseAt;
    private String oldestInvoiceNo;
    private long unpaidPurchaseCount;
    private LocalDateTime lastPaymentAt;
    private String priority;
}
