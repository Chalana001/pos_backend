package com.chala.posapp.dto.order;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.WarrantyPeriodUnit;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderItemRequest {

    @NotNull
    private Long itemId;

    private Long batchId;

    @Positive
    private BigDecimal qty;

    private MeasurementUnit qtyUnit;

    @PositiveOrZero
    private double unitPrice;

    @NotNull
    private DiscountType discountType;

    @PositiveOrZero
    private double discountValue;

    private String warrantyLabel;

    @Positive
    private Integer warrantyPeriodValue;

    private WarrantyPeriodUnit warrantyPeriodUnit;
}
