package com.chala.posapp.dto.stock;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StockTransferItemRequest {
    @NotNull
    private Long itemId;

    @Min(1)
    private int qty;
}
