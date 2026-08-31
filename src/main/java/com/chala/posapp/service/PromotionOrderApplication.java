package com.chala.posapp.service;

import com.chala.posapp.entity.DiscountType;

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
