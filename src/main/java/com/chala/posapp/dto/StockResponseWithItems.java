package com.chala.posapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockResponseWithItems {
    private Long itemId;
    private String barcode;
    private String itemName;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private Long totalQuantity;
}
