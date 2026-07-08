package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// RPT-04: Single GRN row for the purchase/GRN report
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrnReportResponse {
    private Long          grnId;
    private String        grnNo;
    private Long          supplierId;
    private String        supplierName;
    private Long          branchId;
    private String        branchName;
    private double        totalAmount;
    private double        paidAmount;
    private double        dueAmount;
    private String        note;
    private LocalDateTime receivedAt;
    private String        createdByUsername;
}
