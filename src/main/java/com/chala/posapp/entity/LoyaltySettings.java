package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per tenant: whether this shop runs loyalty, and at what rates.
 *
 * <p>Earning and spending are two separate rates on purpose. One number would hide what the
 * scheme costs: earning a point per rupee and spending points back at 0.25 is a 25% return, and
 * an operator should be able to see and set that rather than infer it.
 */
@Entity
@Table(name = "loyalty_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltySettings extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Off by default: a shop buys the module, then sets the rates before anything earns. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Points earned per unit of currency spent, before the tier multiplier. */
    @Column(name = "points_per_currency", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal pointsPerCurrency = BigDecimal.ONE;

    /** What one point is worth when spent. */
    @Column(name = "currency_per_point", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal currencyPerPoint = BigDecimal.ONE;

    /** Below this, points cannot be spent, stops a scheme paying out in one-rupee slices. */
    @Column(name = "min_redemption_points", nullable = false)
    @Builder.Default
    private int minRedemptionPoints = 0;

    /** Ceiling on how much of a bill points may cover. Null means the whole bill. */
    @Column(name = "max_redemption_percent", precision = 5, scale = 2)
    private BigDecimal maxRedemptionPercent;

    /** Points are whole numbers; this decides which way a fraction goes. */
    @Column(name = "round_earned_down", nullable = false)
    @Builder.Default
    private boolean roundEarnedDown = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static LoyaltySettings defaults() {
        return LoyaltySettings.builder().build();
    }
}
