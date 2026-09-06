package com.chala.posapp.dto.loyalty;

import com.chala.posapp.entity.LoyaltySettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltySettingsDto {
    private boolean enabled;
    /** Points earned per unit of currency, before the tier multiplier. */
    private BigDecimal pointsPerCurrency;
    /** What one point is worth when spent. */
    private BigDecimal currencyPerPoint;
    private int minRedemptionPoints;
    /** Ceiling on how much of a bill points may cover. Null means the whole bill. */
    private BigDecimal maxRedemptionPercent;
    private boolean roundEarnedDown;

    public static LoyaltySettingsDto from(LoyaltySettings settings) {
        return LoyaltySettingsDto.builder()
                .enabled(settings.isEnabled())
                .pointsPerCurrency(settings.getPointsPerCurrency())
                .currencyPerPoint(settings.getCurrencyPerPoint())
                .minRedemptionPoints(settings.getMinRedemptionPoints())
                .maxRedemptionPercent(settings.getMaxRedemptionPercent())
                .roundEarnedDown(settings.isRoundEarnedDown())
                .build();
    }
}
