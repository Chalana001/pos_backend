package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

/**
 * One promotion's verdict on one line or bill, for the preview.
 *
 * <p>This is how "why did my promotion not apply?" gets answered on screen instead of by reading
 * the engine. {@code outcome} is the name of a {@code LineDecision.Outcome}.
 */
@Data
@Builder
public class PromotionDecisionResponse {
    private Long promotionId;
    private String promotionName;
    private String outcome;
    private double discount;
}
