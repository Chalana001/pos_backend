package com.chala.posapp.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LowStockResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private int stockQty;
    private int reorderLevel;
}
