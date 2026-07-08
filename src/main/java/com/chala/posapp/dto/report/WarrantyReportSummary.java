package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// RPT-09: Warranty report summary wrapper
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyReportSummary {
    private List<WarrantyReportResponse> items;
    private long totalWarranties;
    private long totalActive;
    private long totalClaimed;
    private long totalExpired;
}
