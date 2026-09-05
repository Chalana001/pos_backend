package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One sale a promotion touched.
 *
 * <p>Sourced from the denormalised columns already on {@code orders} and {@code order_items}
 * rather than a redemption ledger, so the history page works on data every existing shop
 * already has. When the ledger arrives this moves onto it without the shape changing.
 */
@Data
@Builder
public class PromotionRedemptionResponse {
    private Long orderId;
    private String invoiceNo;
    private LocalDateTime soldAt;
    private Long branchId;
    private Long promotionId;
    private String promotionName;
    /** ITEM for a line-level discount, BILL for a whole-order one. */
    private String level;
    private Long itemId;
    private String itemName;
    private double discountAmount;
    private double orderTotal;
}
