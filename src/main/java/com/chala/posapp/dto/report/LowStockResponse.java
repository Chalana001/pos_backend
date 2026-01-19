package com.chala.posapp.dto.report;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LowStockResponse {
    private Long itemId;
    private String itemName;
    private int quantity;
    private int reorderLevel;
}
