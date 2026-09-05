package com.chala.posapp.promotion.engine;

import java.math.BigDecimal;

/**
 * Why one promotion did or did not price one line.
 *
 * <p>This is the answer to "why did my promotion not apply?", which until now had to be worked
 * out by reading the engine. Every promotion in the candidate list gets one of these per line
 * evaluated, including the winner.
 */
public record LineDecision(
        Long promotionId,
        String promotionName,
        Outcome outcome,
        BigDecimal discount
) {

    public enum Outcome {
        /** Won the line and priced it. */
        APPLIED,
        /** Matched and would have discounted, but another promotion gave more. */
        LOST_TO_BETTER,
        /** A bill or customer promotion being asked about a line, or vice versa. */
        WRONG_SCOPE,
        BRANCH_MISMATCH,
        BELOW_MIN_BILL,
        NO_TARGET_MATCH,
        CUSTOMER_MISMATCH,
        /** Would have sold below cost and the promotion does not allow that. */
        BELOW_COST,
        BELOW_MARGIN_FLOOR,
        /** Matched but the resulting discount was zero — an offer price at or above list, say. */
        NO_DISCOUNT,
        /** At bill level: the manual discount was larger, so the promotion stood down. */
        LOST_TO_MANUAL
    }

    static LineDecision of(PromotionSnapshot promotion, Outcome outcome) {
        return new LineDecision(promotion.id(), promotion.name(), outcome, BigDecimal.ZERO);
    }

    static LineDecision of(PromotionSnapshot promotion, Outcome outcome, BigDecimal discount) {
        return new LineDecision(promotion.id(), promotion.name(), outcome, MoneyOps.round2(discount));
    }
}
