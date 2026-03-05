package com.chala.posapp.dto.order;

import com.chala.posapp.entity.DiscountType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderItemRequest {

    @NotNull
    private Long itemId;

    private Long batchId;

    @Min(1)
    private int qty;

    @Min(0)
    private double unitPrice;

    @NotNull
    private DiscountType discountType;

    @Min(0)
    private double discountValue;
}
