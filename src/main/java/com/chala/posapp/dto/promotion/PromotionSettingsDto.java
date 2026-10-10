package com.chala.posapp.dto.promotion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionSettingsDto {
    private boolean approvalRequired;
    private BigDecimal approvalThresholdPercent;
    private BigDecimal approvalThresholdAmount;
    private boolean cashierManualStacking;
    /** True lifts the below-cost guard for every campaign in this shop. */
    private boolean allowBelowCostPromotions;
}
