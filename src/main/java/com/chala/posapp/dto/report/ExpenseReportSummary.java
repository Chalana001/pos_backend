package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// RPT-06: Expense report with category breakdown + grand total
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseReportSummary {
    private List<ExpenseReportResponse> byCategory;
    private double grandTotal;
    private long   totalCount;
}
