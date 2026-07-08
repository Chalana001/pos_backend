package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// RPT-02: Grand-total wrapper around per-item rows
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryValuationSummary {
    private List<InventoryValuationResponse> items;
    private double totalStockValue;
    private double totalPotentialRevenue;
    private double totalPotentialProfit;
    private long   totalItems;
}
