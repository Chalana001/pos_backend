package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-02: Per-item inventory valuation with category grouping
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryValuationResponse {
    private Long   itemId;
    private String barcode;
    private String itemName;
    private String categoryName;
    private String subCategoryName;
    private String itemType;
    private String unit;
    private double qtyOnHand;        // display units
    private double costPrice;
    private double stockValue;        // qtyOnHand * costPrice (in display units)
    private double sellingPrice;
    private double potentialRevenue;  // qtyOnHand * sellingPrice
    private double potentialProfit;   // potentialRevenue - stockValue
}
