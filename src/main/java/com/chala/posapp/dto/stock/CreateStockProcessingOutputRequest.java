package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateStockProcessingOutputRequest {

    @NotNull
    private Long outputItemId;

    @NotNull
    @Positive
    private BigDecimal qty;

    private MeasurementUnit qtyUnit;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    private Boolean waste;
}
