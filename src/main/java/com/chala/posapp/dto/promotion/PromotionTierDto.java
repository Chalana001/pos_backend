package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One step of a TIERED promotion. Set {@code minQty} for a quantity break on a line, or
 * {@code minAmount} for a spend ladder on a bill, not both.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionTierDto {
    private BigDecimal minQty;
    private BigDecimal minAmount;
    private DiscountType discountType;
    private BigDecimal discountValue;
}
