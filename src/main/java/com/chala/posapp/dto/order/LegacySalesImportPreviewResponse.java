package com.chala.posapp.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegacySalesImportPreviewResponse {
    private int csvRows;
    private int totalSales;
    private int importableSales;
    private int blockedSales;
    private int totalItems;
    private int mappedLegacyItems;
    private int unmatchedItemNames;
    private int duplicateSales;
    private int totalMismatchWarnings;
    private double sourceGrandTotal;
    private double importGrandTotal;
    private List<String> unmatchedItems;
    private List<LegacySalesImportIssue> issues;
}
