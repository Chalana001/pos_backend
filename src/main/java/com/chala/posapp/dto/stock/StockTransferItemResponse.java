package com.chala.posapp.dto.stock;

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
