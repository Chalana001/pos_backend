package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-01: Cashier performance per cashier for a date range
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashierPerformanceResponse {
    private Long   cashierUserId;
    private String cashierUsername;
    private Long   orderCount;
    private double totalSales;
    private double totalDiscounts;
    private double avgOrderValue;
    private Long   returnCount;
    private double totalRefunds;
}
