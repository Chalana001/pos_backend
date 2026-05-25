package com.chala.posapp.dto.item;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockProcessingOutputLinkRequest {

    @NotNull
    private Long outputItemId;

    @NotNull
    @Positive
    private BigDecimal defaultQty;

    private Boolean waste;
}
