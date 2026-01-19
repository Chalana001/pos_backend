package com.chala.posapp.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockTransferItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private int qty;
}
