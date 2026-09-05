package com.chala.posapp.entity;

/**
 * How a promotion combines with others competing for the same line or bill.
 *
 * <p>Priority orders the candidates; this decides what happens once more than one qualifies.
 */
public enum StackingMode {
    /** Competes on discount; the single largest wins. The original behaviour. */
    BEST_ONLY,
    /** Applies on top of whatever wins, in priority order. Discounts add; they do not compound. */
    STACKABLE,
    /**
     * Competes like {@link #BEST_ONLY}, but if it wins nothing stacks on that line, and no
     * bill-level promotion applies to the order. "Cannot be combined with any other offer."
     */
    EXCLUSIVE
}
