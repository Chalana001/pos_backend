package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerCommandCenterResponse {
    private Period currentPeriod;
    private Period comparisonPeriod;
    private Metrics current;
    private Metrics comparison;
    private Risks risks;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Period {
        private LocalDate from;
        private LocalDate to;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metrics {
        private double totalSales;
        private double cashSales;
        private double creditSales;
        private double totalDiscount;
        private long totalOrders;
        private double averageOrderValue;
        private double totalRevenue;
        private double totalCost;
        private double grossProfit;
        private double grossMarginPercent;
        private double totalExpenses;
        private double netProfit;
        private double netMarginPercent;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Risks {
        private long lowStockItems;
        private long outOfStockItems;
        private double totalReceivables;
        private double overdue91Plus;
        private long overdueCustomerCount;
        private double saleReturnAmount;
        private double saleReturnRatePercent;
    }
}
