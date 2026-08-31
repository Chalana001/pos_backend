package com.chala.posapp.dto.purchase;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseImportRowData {
    private int rowNumber;
    private String barcode;
    private String name;

    private Long matchedItemId;
    private String matchedName;
    private String matchedBarcode;
    private MeasurementUnit qtyUnit;

    private BigDecimal costPrice;
    private BigDecimal sellPrice;
    private BigDecimal qty;
    private LocalDate expiry;

    private PurchaseImportRowStatus status;
    private String message;
}
