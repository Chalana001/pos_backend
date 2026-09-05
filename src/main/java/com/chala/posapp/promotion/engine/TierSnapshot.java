package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionTier;

import java.math.BigDecimal;

/** One step of a TIERED promotion, detached from the database. */
public record TierSnapshot(
        BigDecimal minQty,
        BigDecimal minAmount,
        DiscountType discountType,
        BigDecimal discountValue
) {

    public static TierSnapshot from(PromotionTier tier) {
        return new TierSnapshot(tier.getMinQty(), tier.getMinAmount(), tier.getDiscountType(), tier.getDiscountValue());
    }
}
