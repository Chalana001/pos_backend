package com.chala.posapp.repository;

public interface ProfitSummaryProjection {
    Number getTotalRevenue();

    Number getTotalCost();

    Number getGrossProfit();
}
