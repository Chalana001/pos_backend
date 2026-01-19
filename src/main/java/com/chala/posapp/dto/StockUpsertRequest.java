package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StockUpsertRequest {

    @NotNull
    private Long branchId;

    @NotNull
    private Long itemId;

    @Min(0)
    private int quantity;
}
