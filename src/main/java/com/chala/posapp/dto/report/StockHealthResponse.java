package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class StockHealthResponse {
    private int salesWindowDays;
    private int targetCoverDays;
    private long totalItems;
    private long outOfStockItems;
    private long negativeStockItems;
    private long belowReorderItems;
    private long deadStockItems;
    private long itemsWithExpiredStock;
    private long itemsExpiringSoon;
    private double deadStockValue;
    private double estimatedReorderCost;
    private List<ItemHealth> items;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ItemHealth {
        private Long itemId;
        private String barcode;
        private String itemName;
        private String unit;
        private double qtyOnHand;
        private double reorderLevel;
        private double costPrice;
        private double sellingPrice;
        private double stockValue;
        private double soldLast90Days;
        private double averageDailySales;
        private Double estimatedDaysOfStock;
        private double suggestedReorderQty;
        private double estimatedReorderCost;
        private LocalDateTime lastSoldAt;
        private String preferredSupplier;
        private LocalDate nearestExpiryDate;
        private String status;
    }
}
