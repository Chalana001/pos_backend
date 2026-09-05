package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

/**
 * "Would this code work right now?" — answered without consuming anything, so the till can show
 * the result while the cashier is still typing. {@code message} explains a refusal in the
 * customer's terms: expired, fully redeemed, already used by this customer.
 */
@Data
@Builder
public class CodeCheckResponse {
    private String code;
    private boolean valid;
    private Long promotionId;
    private String promotionName;
    private String message;
}
