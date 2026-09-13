package com.chala.posapp.dto.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * "What would these prices do?" — asked while the operator is still typing, so the margin
 * badges in the price table are the same numbers the save path will enforce.
 */
@Data
public class PromotionPriceCheckRequest {

    @Valid
    @NotNull
    private List<PromotionItemLine> items;

    /** The promotion's branch, or null for every branch — decides whose batches are checked. */
    private Long branchId;

    /** Falls back to each item's own rate when a line carries no price of its own. */
    /** Which mechanic the builder is previewing; only a profit share prices differently here. */
    private com.chala.posapp.entity.PromotionEffectType effectType;

    private com.chala.posapp.entity.DiscountType discountType;
    private double discountValue;

    private boolean allowBelowCost;
    private java.math.BigDecimal marginFloorPercent;
}
