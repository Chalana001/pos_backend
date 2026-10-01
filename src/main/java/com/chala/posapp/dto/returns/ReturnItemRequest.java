package com.chala.posapp.dto.returns;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReturnItemRequest {

    // Must point to a valid order_items.id belonging to this order
    @NotNull(message = "orderItemId is required")
    private Long orderItemId;

    // How many units to return, in DISPLAY units (what the till shows), not base units.
    // Measured goods can be returned fractionally: 0.7 KG of a 2 KG sale.
    @NotNull(message = "returnQty is required")
    @DecimalMin(value = "0.001", message = "returnQty must be at least 0.001", inclusive = true)
    @Digits(integer = 9, fraction = 3, message = "returnQty supports up to 3 decimal places")
    private BigDecimal returnQty;
}
