package com.chala.posapp.dto.promotion;

import com.chala.posapp.dto.order.OrderItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PromotionPreviewRequest {
    private Long branchId;
    private Long customerId;
    private double billDiscount;

    /** A promo code the customer presented, if any. One per cart. */
    private String promotionCode;

    @Valid
    @NotNull
    private List<OrderItemRequest> items;
}
