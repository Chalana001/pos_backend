package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionTarget;

import java.math.BigDecimal;

/**
 * One row of a promotion's target list, detached from the database.
 *
 * <p>Exactly one of the four ids is set, matching the scope of the owning promotion. The
 * price fields are the per-item override introduced by V34 and are usually null — a null
 * row inherits the promotion's own discount.
 */
public record TargetSnapshot(
        Long itemId,
        Long categoryId,
        Long subCategoryId,
        Long customerId,
        BigDecimal offerPrice,
        DiscountType discountType,
        BigDecimal discountValue
) {

    public static TargetSnapshot from(PromotionTarget target) {
        return new TargetSnapshot(
                target.getItemId(),
                target.getCategoryId(),
                target.getSubCategoryId(),
                target.getCustomerId(),
                target.getOfferPrice(),
                target.getDiscountType(),
                target.getDiscountValue()
        );
    }

    /** True when this row names its own rate rather than inheriting the promotion's. */
    public boolean hasOwnRate() {
        return discountType != null && discountType != DiscountType.NONE && discountValue != null;
    }
}
