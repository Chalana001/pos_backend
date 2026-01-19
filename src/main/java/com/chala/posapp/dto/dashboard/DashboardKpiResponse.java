package com.chala.posapp.dto.dashboard;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DashboardKpiResponse {
    private double todaySales;
    private double cashSales;
    private double creditSales;
    private double todayDiscount;
    private long todayOrders;

    private double todayExpenses;
    private double todayCashDrops;

    private long lowStockCount;

    private double totalDue;
}
