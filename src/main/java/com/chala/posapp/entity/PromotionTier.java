package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One step of a {@link PromotionEffectType#TIERED} promotion.
 *
 * <p>Exactly one threshold is set: {@code minQty} for a quantity break on a line ("3 or more at
 * 90 each"), {@code minAmount} for a spend ladder on a bill ("Rs. 500 off over Rs. 5,000"). The
 * highest threshold the line or bill reaches is the one that applies, and its rate covers the
 * whole line or bill, not just the units above the threshold.
 */
@Entity
@Table(
        name = "promotion_tiers",
        indexes = @Index(name = "idx_promotion_tier_promotion", columnList = "promotion_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionTier extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    /** In the item's primary unit — pieces, kilograms, litres. */
    @Column(name = "min_qty", precision = 12, scale = 3)
    private BigDecimal minQty;

    @Column(name = "min_amount", precision = 19, scale = 4)
    private BigDecimal minAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountValue;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;
}
