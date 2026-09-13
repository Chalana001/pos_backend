package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A customer's points.
 *
 * <p>{@code pointsBalance} is a running total kept alongside the ledger rather than derived on
 * every read, a balance is looked at far more often than it changes. Every transaction records
 * the balance it produced, so a disputed figure can be walked back through
 * {@code loyalty_transactions} rather than taken on trust.
 */
@Entity
@Table(
        name = "loyalty_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_loyalty_account_customer", columnNames = "customer_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyAccount extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "points_balance", nullable = false)
    @Builder.Default
    private int pointsBalance = 0;

    /** Never decreases. Tier reads from this, so spending points cannot demote anyone. */
    @Column(name = "lifetime_points", nullable = false)
    @Builder.Default
    private int lifetimePoints = 0;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
