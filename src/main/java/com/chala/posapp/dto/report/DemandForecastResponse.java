package com.chala.posapp.dto.report;

import lombok.*;

import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class DemandForecastResponse {
    private int historyDays;
    private int forecastDays;
    private int targetCoverDays;
    private long totalItems;
    private long actionableItems;
    private long lowConfidenceItems;
    private double projectedRevenue;
    private double estimatedReorderCost;
    private List<ItemForecast> items;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ItemForecast {
        private Long itemId;
        private String barcode;
        private String itemName;
        private String unit;
        private double qtyOnHand;
        private double soldLast30Days;
        private double soldPrevious60Days;
        private int activeSalesDays;
        private double averageDailyDemand;
        private double projectedDemand;
        private double projectedRevenue;
        private Double estimatedStockoutDays;
        private double suggestedReorderQty;
        private double estimatedReorderCost;
        private double reorderLevelGapQty;
        private String trend;
        private String confidence;
        private String warning;
    }
}
