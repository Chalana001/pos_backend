package com.chala.posapp.dto.loyalty;

import com.chala.posapp.entity.LoyaltyTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyTierDto {
    private Long id;
    private String name;
    private int minLifetimePoints;
    private BigDecimal earnMultiplier;
    private int sortOrder;
    @Builder.Default
    private boolean active = true;

    public static LoyaltyTierDto from(LoyaltyTier tier) {
        return LoyaltyTierDto.builder()
                .id(tier.getId())
                .name(tier.getName())
                .minLifetimePoints(tier.getMinLifetimePoints())
                .earnMultiplier(tier.getEarnMultiplier())
                .sortOrder(tier.getSortOrder())
                .active(tier.isActive())
                .build();
    }
}
