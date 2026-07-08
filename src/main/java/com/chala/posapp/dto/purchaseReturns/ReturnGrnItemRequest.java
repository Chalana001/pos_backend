package com.chala.posapp.dto.purchaseReturns;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReturnGrnItemRequest {

    @NotNull(message = "grnItemId is required")
    private Long grnItemId;

    @NotNull(message = "returnQty is required")
    @Min(value = 1, message = "returnQty must be at least 1")
    private Integer returnQty;
}
