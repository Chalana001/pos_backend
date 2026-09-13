package com.chala.posapp.entity;

/**
 * What kind of offer a promotion is. Scope and targets say what it applies to; this says how.
 *
 * <p>{@link #BUNDLE} and {@link #CHEAPEST_FREE} look across the whole cart and so are
 * evaluated at bill level even when their targets are items or categories. The rest price
 * one line at a time.
 */
public enum PromotionEffectType {
    /** The original behaviour: {@code discountType}/{@code discountValue} off list price. */
    DISCOUNT,
    /** Every targeted item sells at {@code discountValue}. */
    FIXED_PRICE,
    /** Per line: buy {@code buyQty}, get {@code getQty} more of the same item free. */
    BUY_X_GET_Y_FREE,
    /** Quantity breaks on a line, or spend ladders on a bill, from {@code promotion_tiers}. */
    TIERED,
    /** Any {@code buyQty} eligible units for {@code discountValue} in total. Cart-level. */
    BUNDLE,
    /** Every {@code buyQty} eligible units, the cheapest one is free. Cart-level. */
    CHEAPEST_FREE,
    /**
     * The customer gets {@code discountValue} percent of the line's <em>profit</em>, not of its
     * price: an item making 100 gives 10 away at 10%, one making 200 gives 20.
     *
     * <p>The point is that it cannot sell below cost. A flat percentage off price is a different
     * cut on every item depending on what it cost, and on a thin-margin line it is a loss; a
     * share of margin is the same generosity everywhere and is bounded by the margin itself.
     *
     * <p>A line whose cost is unknown gives nothing away, a share of an unknown profit would
     * be a guess, and guessing here spends real money.
     */
    PROFIT_SHARE;

    public boolean isCartLevel() {
        return this == BUNDLE || this == CHEAPEST_FREE;
    }
}
