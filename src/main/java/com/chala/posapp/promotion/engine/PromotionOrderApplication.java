package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;

/** The priced outcome at bill level. */
public record PromotionOrderApplication(
        Long promotionId,
        String promotionName,
        DiscountType discountType,
        double discountValue,
        double manualBillDiscountAmount,
        double promotionDiscountAmount,
        double appliedDiscountAmount,
        double baseTotal,
        double finalTotal,
        boolean promotionApplied
) {
}
