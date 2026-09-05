package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the builder shows before save: whether this will need a second admin, how deep its
 * deepest cut is, and which running promotions it will collide with.
 */
@Data
@Builder
public class PromotionCheckResponse {
    private boolean approvalRequired;
    private BigDecimal deepestDiscountPercent;
    private List<Warning> warnings;

    @Data
    @Builder
    public static class Warning {
        /** OVERLAP, ALREADY_ENDED, NEVER_RUNS */
        private String code;
        private String message;
        private Long promotionId;
        private String promotionName;
    }
}
