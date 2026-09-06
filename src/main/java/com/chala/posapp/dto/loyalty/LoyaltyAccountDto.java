package com.chala.posapp.dto.loyalty;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A customer's points as the till needs them: the balance, what it is worth, and whether it can
 * be spent at all — so the cashier is not offered a redemption the scheme will refuse.
 */
@Data
@Builder
public class LoyaltyAccountDto {
    private Long customerId;
    private int pointsBalance;
    private int lifetimePoints;
    private String tierName;
    private BigDecimal earnMultiplier;
    /** What the balance would take off a bill today. */
    private BigDecimal pointsValue;
    private boolean enabled;
    private int minRedemptionPoints;
}
