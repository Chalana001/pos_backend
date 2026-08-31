package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-06: Expense totals per category for a date range
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseReportResponse {
    private String category;           // expense type name
    private Long   expenseTypeId;
    private long   count;
    private double totalAmount;
    private double avgAmount;
}
