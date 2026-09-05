package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;

/**
 * The priced outcome for one line.
 *
 * <p>{@code discountType}/{@code discountValue} are what the caller replays to rebuild the
 * final unit price, so they must reproduce the price actually charged — see the note in
 * {@link PromotionEvaluator#evaluateLine} on why a rate collapses to FIXED when anything
 * altered it.
 */
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
