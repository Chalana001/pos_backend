package com.chala.posapp.dto.returns;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReturnItemRequest {

    // Must point to a valid order_items.id belonging to this order
    @NotNull(message = "orderItemId is required")
    private Long orderItemId;

    // How many units to return (in base units, same as OrderItem.qty)
    @NotNull(message = "returnQty is required")
    @Min(value = 1, message = "returnQty must be at least 1")
    private Integer returnQty;
}
