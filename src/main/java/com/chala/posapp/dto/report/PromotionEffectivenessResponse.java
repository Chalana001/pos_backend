package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-08: Per-promotion effectiveness for a date range
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionEffectivenessResponse {
    private Long   promotionId;
    private String promotionName;
    private String discountType;
    private double discountValue;
    private long   timesApplied;
    private double totalDiscountGiven;
    private double totalRevenue;        // gross revenue on orders where this promotion fired
    private double avgOrderValue;
}
