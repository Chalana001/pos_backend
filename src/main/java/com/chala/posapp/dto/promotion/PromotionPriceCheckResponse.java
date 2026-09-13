package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class PromotionPriceCheckResponse {

    private List<Line> items;
    private int belowCostCount;
    private int belowFloorCount;
    private BigDecimal averageDiscountPercent;
    private BigDecimal maxLineDiscount;

    /**
     * One priced row. {@code status} is what the table renders: OK, LOW_MARGIN, BELOW_COST, or
     * ABOVE_NORMAL_PRICE, named rather than a bare boolean so the UI does not re-derive the
     * rule and drift from the engine.
     */
    @Data
    @Builder
    public static class Line {
        private Long itemId;
        private String itemName;
        private String barcode;
        private BigDecimal normalPrice;
        private BigDecimal costPrice;

        /**
         * The spread of live batch prices behind {@code normalPrice}, when there is one.
         *
         * <p>An item sold from two batches has two prices, and the promotion means something
         * different against each. Null when the item has no stocked batches, and equal to each
         * other when every batch agrees, the table only says anything when they differ.
         */
        private BigDecimal minBatchPrice;
        private BigDecimal maxBatchPrice;
        private int batchCount;
        private BigDecimal offerPrice;
        private BigDecimal discountAmount;
        private BigDecimal discountPercent;
        private BigDecimal marginPercent;
        private String status;
        private String message;
    }
}
