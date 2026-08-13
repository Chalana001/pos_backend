package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-05: Per-item stock movement for a date range
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementResponse {
    private Long   itemId;
    private String barcode;
    private String itemName;
    private String unit;

    private double openingStock;     // stock before the date range (in raw units)
    private double purchasesIn;      // quantity received via GRN
    private double salesOut;         // quantity sold
    private double returnsIn;        // customer returns (stock back in)
    private double purchaseReturnsOut; // quantity returned to suppliers
    private double adjustmentsNet;   // stock adjustments (positive = in, negative = out)
    private double transfersIn;      // transfers received
    private double transfersOut;     // transfers sent
    private double processingIn;     // stock created by processing
    private double processingOut;    // source stock consumed by processing
    private double closingStock;
}
