package com.chala.posapp.dto.promotion;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PromotionPreviewResponse {
    private List<PromotionPreviewItemResponse> items;
    private double promotionDiscountTotal;
    private Long billPromotionId;
    private String billPromotionName;
    private double manualBillDiscountAmount;
    private double billPromotionDiscountAmount;
    private double appliedBillDiscountAmount;
    private double finalTotal;
    private boolean billPromotionApplied;
}
