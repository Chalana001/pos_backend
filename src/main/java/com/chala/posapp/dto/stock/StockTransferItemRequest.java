package com.chala.posapp.dto.stock;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StockTransferItemRequest {

    @NotNull
    private Long itemId;

    @NotNull(message = "Batch ID is required")
    private Long batchId;

    @Min(1)
    private int qty;
}
