package com.chala.posapp.dto.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateStockTransferRequest {

    @NotNull
    private Long fromBranchId;

    @NotNull
    private Long toBranchId;

    @Valid
    @NotNull
    private List<StockTransferItemRequest> items;

    private String note;
}
