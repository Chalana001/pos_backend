package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RPT-08: what a promotion actually did, over a date range.
 *
 * <p>Read from the redemption ledger, so it covers item, category, bill and customer
 * promotions alike, the original version keyed on {@code orders.bill_promotion_id} and could
 * only ever see bill-level ones, which meant a shop running item campaigns opened this report
 * and saw nothing.
 *
 * <p>{@code totalRevenue} is turnover on the baskets the promotion appeared on. It is not
 * uplift and never was: the customer might have bought the same basket anyway. What is new
 * here is the margin the discount actually cost, and a basket comparison against orders in the
 * same period that no promotion touched, an indication of lift, not a controlled experiment.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionEffectivenessResponse {
    private Long   promotionId;
    private String promotionName;
    private String scope;
    private String effectType;
    private String discountType;
    private double discountValue;

    /** Orders the promotion discounted, counted once each however many lines it touched. */
    private long   timesApplied;
    private double totalDiscountGiven;
    /** Turnover on those orders. Not uplift. */
    private double totalRevenue;
    private double avgOrderValue;

    /** Units moved on discounted lines, in the item's primary unit. Zero for bill promotions. */
    private double unitsMoved;
    private long   itemsDiscounted;

    /** Gross margin on the affected baskets before this promotion's discount, and after it. */
    private double grossMarginBefore;
    private double grossMarginAfter;
    /** The discount as a percentage of the margin it came out of. */
    private Double marginErosionPercent;

    /** Average basket on promoted orders against orders no promotion touched, same period. */
    private double avgBasketWithPromotion;
    private double avgBasketWithout;
    private Double basketLiftPercent;

    /** Coded campaigns only: how many codes were minted and how many were used at least once. */
    private long   codesIssued;
    private long   codesUsed;
    private Double codeRedemptionRatePercent;
}
