package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;

import java.util.List;

/**
 * The priced outcome for one line.
 *
 * <p>{@code discountType}/{@code discountValue} are what the caller replays to rebuild the
 * final unit price, so they must reproduce the price actually charged. See the note in
 * {@link PromotionEvaluator#evaluateLine} on why a rate collapses to FIXED when anything
 * altered it.
 *
 * <p>With stacking, more than one promotion can price a line. {@code promotionId} is the one
 * that gave the most, what {@code order_items.promotion_id} records, and
 * {@code appliedPromotionIds} lists all of them in the order they applied. Attribution of the
 * combined amount to a single id is a known limitation until the redemption ledger lands.
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
        boolean promotionApplied,
        List<Long> appliedPromotionIds,
        /** True when the winner is EXCLUSIVE: bill-level promotions must stand down on this order. */
        boolean exclusive
) {
}
