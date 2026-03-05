package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.stock.StockAdjustmentType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateStockAdjustmentRequest {

    @NotNull
    private Long branchId;

    @NotNull
    private Long itemId;

    @NotNull
    private StockAdjustmentType type;

    @Min(1)
    private int qty;

    @NotBlank
    private String reason;
}
