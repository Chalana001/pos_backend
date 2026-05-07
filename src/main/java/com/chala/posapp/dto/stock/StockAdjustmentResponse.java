package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.stock.StockAdjustmentType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentResponse {
    private Long id;
    private Long branchId;
    private String branchName;
    private Long itemId;
    private String itemBarcode;
    private String itemName;
    private StockAdjustmentType type;
    private int qtyChange;
    private BigDecimal displayQtyChange;
    private MeasurementUnit qtyUnit;
    private String reason;
    private Long userId;
    private String username;
    private LocalDateTime createdAt;
}
