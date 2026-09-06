package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A rung of the loyalty ladder. The highest tier whose threshold a customer has passed is the
 * one they are on, and its multiplier applies to what they earn next.
 *
 * <p>Measured on lifetime points rather than the current balance: spending points must not
 * demote anyone.
 */
@Entity
@Table(
        name = "loyalty_tiers",
        uniqueConstraints = @UniqueConstraint(name = "uk_loyalty_tier_name", columnNames = "name"),
        indexes = @Index(name = "idx_loyalty_tier_threshold", columnList = "min_lifetime_points")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyTier extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "min_lifetime_points", nullable = false)
    @Builder.Default
    private int minLifetimePoints = 0;

    @Column(name = "earn_multiplier", nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal earnMultiplier = BigDecimal.ONE;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
