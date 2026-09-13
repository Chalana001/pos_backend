package com.chala.posapp.dto.loyalty;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A customer's points as the till needs them: the balance, what it is worth, and whether it can
 * be spent at all, so the cashier is not offered a redemption the scheme will refuse.
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

    /**
     * The scheme's own terms, so the till can price a redemption the same way the server will.
     *
     * <p>Without these the cart could only guess, dividing pointsValue by the balance for a
     * rate, and knowing nothing of the ceiling, and a cashier would read out a total the
     * server then disagreed with.
     */
    private BigDecimal currencyPerPoint;
    private BigDecimal maxRedemptionPercent;
}
