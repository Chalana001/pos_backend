package com.chala.posapp.dto.report;

import com.chala.posapp.dto.PageResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-04: GRN report with page + totals
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrnReportSummary {
    private PageResponse<GrnReportResponse> page;
    private double totalAmount;
    private double totalPaid;
    private double totalDue;
    private double totalReturns;
    private double netReceivedAmount;
    private long uniquePurchaseCount;
}
