package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * The answer to "how do I know this won't cost me two million", the same evaluator that will
 * price sales, run over the sales the shop actually made.
 */
@Data
@Builder
public class PromotionSimulationResponse {
    private int days;
    private int ordersScanned;
    private int ordersAffected;
    private BigDecimal affectedRatePercent;
    private BigDecimal projectedDiscount;
    private BigDecimal averageDiscountPerAffectedOrder;
    /** The single most expensive order, the worst case one sale can cost. */
    private BigDecimal maxOrderDiscount;
    private BigDecimal projectedDailyDiscount;
    /** Days the budget would have lasted at that rate; null when no budget is set. */
    private BigDecimal budgetDaysRemaining;
    private BigDecimal grossMarginBefore;
    private BigDecimal grossMarginAfter;
    private BigDecimal marginErosionPercent;
    private List<TopItem> topItems;
    /** True when the query hit its cap and older orders were not scanned. */
    private boolean truncated;

    @Data
    @Builder
    public static class TopItem {
        private Long itemId;
        private String itemName;
        private int timesDiscounted;
        private BigDecimal discount;
    }
}
