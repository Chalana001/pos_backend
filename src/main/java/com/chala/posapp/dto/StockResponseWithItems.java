package com.chala.posapp.dto;

import java.time.LocalDateTime;
import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockResponseWithItems {
    private Long id;
    private Long itemId;
    private String barcode;
    private String name;
    private double costPrice;
    private double sellingPrice;
    private int quantity;
    private LocalDateTime updatedAt;
}
