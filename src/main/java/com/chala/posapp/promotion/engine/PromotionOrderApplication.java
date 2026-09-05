package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;

import java.util.List;

/** The priced outcome at bill level. {@code appliedPromotionIds} lists every promotion that contributed. */
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
        boolean promotionApplied,
        List<Long> appliedPromotionIds
) {
}
