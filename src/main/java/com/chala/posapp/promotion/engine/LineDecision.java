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
        /** Won the line, or stacked onto it, and priced it. */
        APPLIED,
        /** Matched and would have discounted, but another promotion gave more. */
        LOST_TO_BETTER,
        /** Would have stacked, but the winner is EXCLUSIVE — or a line winner was, at bill level. */
        BLOCKED_BY_EXCLUSIVE,
        /** The cashier typed a discount, but the applied promotion does not allow one on top. */
        MANUAL_BLOCKED,
        /** A bill or customer promotion being asked about a line, or vice versa. */
        WRONG_SCOPE,
        BRANCH_MISMATCH,
        BELOW_MIN_BILL,
        NO_TARGET_MATCH,
        CUSTOMER_MISMATCH,
        /** Would have sold below cost and the promotion does not allow that. */
        BELOW_COST,
        BELOW_MARGIN_FLOOR,
        /** TIERED: the line or bill did not reach the lowest step. */
        NO_TIER_REACHED,
        /** Matched but the resulting discount was zero — an offer price at or above list, say. */
        NO_DISCOUNT,
        /** At bill level: the manual discount was larger, so the promotion stood down. */
        LOST_TO_MANUAL,
        /** The promotion is code-gated and no valid code of its was presented. */
        CODE_REQUIRED,
        /** The promotion has been redeemed as many times as it allows. */
        LIMIT_REACHED,
        /** The promotion has given away its whole budget. */
        BUDGET_EXHAUSTED,
        /** This customer has had this promotion as often as it allows. */
        CUSTOMER_LIMIT_REACHED
    }

    /** For gating decisions, which have no snapshot at hand. */
    public static LineDecision of(Long promotionId, String promotionName, Outcome outcome) {
        return new LineDecision(promotionId, promotionName, outcome, BigDecimal.ZERO);
    }

    static LineDecision of(PromotionSnapshot promotion, Outcome outcome) {
        return new LineDecision(promotion.id(), promotion.name(), outcome, BigDecimal.ZERO);
    }

    static LineDecision of(PromotionSnapshot promotion, Outcome outcome, BigDecimal discount) {
        return new LineDecision(promotion.id(), promotion.name(), outcome, MoneyOps.round2(discount));
    }
}
