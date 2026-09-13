package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionScope;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A campaign that has finished, with what it actually did.
 *
 * <p>Counts both halves of a promotion. RPT-08 keys only on {@code orders.bill_promotion_id},
 * so item and category campaigns, whose discounts live on {@code order_items}, report as
 * nothing at all there. A shop running item promotions sees an empty report and concludes
 * none of them fired.
 */
@Data
@Builder
public class PromotionHistoryResponse {
    private Long id;
    private String name;
    private PromotionScope scope;
    private DiscountType discountType;
    private double discountValue;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long branchId;
    private boolean active;
    private boolean deleted;
    /** LIVE, SCHEDULED, PAUSED, ENDED or ARCHIVED, computed from dates and flags, not stored. */
    private String status;
    private int targetCount;
    private long timesApplied;
    private double totalDiscountGiven;
    private double totalRevenue;
}
