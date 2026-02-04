package com.chala.posapp.dto;

import com.chala.posapp.entity.StockAdjustmentType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentResponse {
    private Long id;
    private Long branchId;
    private Long itemId;
    private String itemBarcode;
    private String itemName;
    private StockAdjustmentType type;
    private int qtyChange;
    private String reason;
    private Long userId;
    private LocalDateTime createdAt;
}
