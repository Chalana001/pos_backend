package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionTarget;

import java.math.BigDecimal;

/**
 * One row of a promotion's target list, detached from the database.
 *
 * <p>Exactly one of the four ids is set, matching the scope of the owning promotion. The
 * price fields are the per-item override introduced by V34 and are usually null, a null
 * row inherits the promotion's own discount.
 */
public record TargetSnapshot(
        Long itemId,
        Long categoryId,
        Long subCategoryId,
        Long customerId,
        BigDecimal offerPrice,
        DiscountType discountType,
        BigDecimal discountValue,
        /**
         * Set instead of {@link #customerId} when the target names a rule rather than a person.
         * The evaluator ignores it. See {@link #asCustomer}.
         */
        Long segmentId
) {

    /** Everything but a segment, which is every target except the one kind the gate rewrites. */
    public TargetSnapshot(Long itemId, Long categoryId, Long subCategoryId, Long customerId,
                          BigDecimal offerPrice, DiscountType discountType, BigDecimal discountValue) {
        this(itemId, categoryId, subCategoryId, customerId, offerPrice, discountType, discountValue, null);
    }

    public static TargetSnapshot from(PromotionTarget target) {
        return new TargetSnapshot(
                target.getItemId(),
                target.getCategoryId(),
                target.getSubCategoryId(),
                target.getCustomerId(),
                target.getOfferPrice(),
                target.getDiscountType(),
                target.getDiscountValue(),
                target.getSegmentId()
        );
    }

    /**
     * The same target naming one customer explicitly.
     *
     * <p>How a segment reaches the engine: {@code PromotionGate} resolves the sale's customer
     * into their segments and rewrites a segment target as this, so the engine keeps matching
     * on explicit ids and never learns what a segment is. That is what lets segment targeting
     * exist without touching the evaluator, the JS port, or the fixture corpus.
     */
    public TargetSnapshot asCustomer(Long resolvedCustomerId) {
        return new TargetSnapshot(itemId, categoryId, subCategoryId, resolvedCustomerId,
                offerPrice, discountType, discountValue, segmentId);
    }

    /** True when this row names its own rate rather than inheriting the promotion's. */
    public boolean hasOwnRate() {
        return discountType != null && discountType != DiscountType.NONE && discountValue != null;
    }
}
