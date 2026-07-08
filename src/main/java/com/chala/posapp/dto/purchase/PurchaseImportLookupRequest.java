package com.chala.posapp.dto.purchase;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PurchaseImportLookupRequest {
    private int rowNumber;
    private String barcode;
    private String name;
    private BigDecimal costPrice;
    private BigDecimal sellPrice;
    private BigDecimal qty;
    private LocalDate expiry;
}
