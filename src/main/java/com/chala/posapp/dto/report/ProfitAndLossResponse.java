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
public class ProfitAndLossResponse {
    private Period currentPeriod;
    private Period comparisonPeriod;
    private Statement current;
    private Statement comparison;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Period { private LocalDate from; private LocalDate to; }

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Statement {
        private double itemRevenue;
        private double billDiscounts;
        private double salesReturns;
        private double netRevenue;
        private double costOfGoodsSold;
        private double returnedCost;
        private double grossProfit;
        private double grossMarginPercent;
        private double operatingExpenses;
        private double netProfit;
        private double netMarginPercent;
        private long revenueLineCount;
        private long missingCostLineCount;
        private double costCoveragePercent;
    }
}
