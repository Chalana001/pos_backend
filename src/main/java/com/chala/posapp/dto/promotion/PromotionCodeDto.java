package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.PromotionCode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PromotionCodeDto {
    private Long id;
    private String code;
    private PromotionCode.CodeType codeType;
    private Integer maxRedemptions;
    private int redemptionsUsed;
    private Integer perCustomerLimit;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private boolean active;
    private String batchId;
    private LocalDateTime createdAt;

    public static PromotionCodeDto from(PromotionCode code) {
        return PromotionCodeDto.builder()
                .id(code.getId())
                .code(code.getCode())
                .codeType(code.getCodeType())
                .maxRedemptions(code.getMaxRedemptions())
                .redemptionsUsed(code.getRedemptionsUsed())
                .perCustomerLimit(code.getPerCustomerLimit())
                .validFrom(code.getValidFrom())
                .validTo(code.getValidTo())
                .active(code.isActive())
                .batchId(code.getBatchId())
                .createdAt(code.getCreatedAt())
                .build();
    }
}
