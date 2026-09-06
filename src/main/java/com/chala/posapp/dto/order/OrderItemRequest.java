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

    // ── offline tills only ──────────────────────────────────────────────────────────────
    // An offline till prices with its own copy of the engine and reports what fired. These
    // are honoured only on the offline import path; an online sale is priced by the server
    // and anything a client sends here is ignored.
    private Long promotionId;
    private String promotionName;
    private Double promotionDiscountAmount;

    private String warrantyLabel;

    @Positive
    private Integer warrantyPeriodValue;

    private WarrantyPeriodUnit warrantyPeriodUnit;
}
