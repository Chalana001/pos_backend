package com.chala.posapp.service;

import com.chala.posapp.entity.DiscountType;

public record PromotionApplication(
        Long promotionId,
        String promotionName,
        DiscountType discountType,
        double discountValue,
        double manualDiscountAmount,
        double promotionDiscountAmount,
        double appliedDiscountAmount,
        double baseLineTotal,
        double finalLineTotal,
        boolean promotionApplied
) {
}
