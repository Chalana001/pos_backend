package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromotionPreviewItemResponse {
    private Long itemId;
    private Long promotionId;
    private String promotionName;
    private DiscountType discountType;
    private double discountValue;
    private double manualDiscountAmount;
    private double promotionDiscountAmount;
    private double appliedDiscountAmount;
    private double baseLineTotal;
    private double finalLineTotal;
    private boolean promotionApplied;
}
