package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.stock.StockAdjustmentDirection;
import com.chala.posapp.entity.stock.StockAdjustmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateStockAdjustmentRequest {

    @NotNull
    private Long branchId;

    @NotNull
    private Long itemId;

    @NotNull(message = "Batch ID is required")
    private Long batchId;

    @NotNull
    private StockAdjustmentType type;

    @Positive
    private BigDecimal qty;

    private StockAdjustmentDirection direction;

    private MeasurementUnit qtyUnit;

    @NotBlank
    private String reason;
}
