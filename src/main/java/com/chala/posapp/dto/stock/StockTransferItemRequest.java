package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockTransferItemRequest {

    @NotNull
    private Long itemId;

    @NotNull(message = "Batch ID is required")
    private Long batchId;

    @Positive
    private BigDecimal qty;

    private MeasurementUnit qtyUnit;
}
