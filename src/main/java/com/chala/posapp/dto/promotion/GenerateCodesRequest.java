package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.PromotionCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Mint codes for a promotion. Either one named code ({@code code}) or a batch of {@code count}
 * random ones under {@code prefix}. A batch shares a {@code batchId} so it can be exported or
 * retired together.
 */
@Data
public class GenerateCodesRequest {

    /** A specific code to create, e.g. WELCOME10. Leave empty to generate random ones. */
    private String code;

    @Min(1)
    @Max(5000)
    private int count = 1;

    /** Up to 8 characters, prepended to generated codes. */
    private String prefix;

    private PromotionCode.CodeType codeType = PromotionCode.CodeType.PUBLIC;

    private Integer maxRedemptions;

    private Integer perCustomerLimit;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;
}
