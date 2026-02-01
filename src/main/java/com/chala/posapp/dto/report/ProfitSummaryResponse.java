package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProfitSummaryResponse {
    private double totalRevenue;      // මුළු විකුණුම්
    private double totalCost;         // බඩු වල වියදම (COGS)
    private double grossProfit;       // දළ ලාභය (Revenue - Cost)
    private double totalExpenses;     // අමතර වියදම් (Expenses)
    private double netProfit;         // ශුද්ධ ලාභය (Gross Profit - Expenses)
}