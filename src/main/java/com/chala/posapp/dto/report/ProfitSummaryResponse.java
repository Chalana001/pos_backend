package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProfitSummaryResponse {
    private double totalRevenue;
    private double totalCost;
    private double grossProfit;
    private double totalExpenses;
    private double netProfit;
}