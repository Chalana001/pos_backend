package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A rule that picks out customers, so a promotion can target "everyone who spent over
 * Rs. 50,000" instead of a hand-typed list of ids that goes stale the next day.
 *
 * <p>Deliberately five thresholds rather than a query language. These are what a shop asks
 * for, each is a column an operator can see and change, and each maps to a single aggregate
 * over completed orders. A null threshold is not a condition.
 *
 * <p>Membership is materialised into {@code customer_segment_members} and recomputed on
 * demand, a promotion must not run an aggregate over every order in the checkout path.
 */
@Entity
@Table(
        name = "customer_segments",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_segment_name", columnNames = "name")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSegment extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String description;

    /** Lifetime spend across completed orders. */
    @Column(name = "min_total_spend", precision = 19, scale = 4)
    private BigDecimal minTotalSpend;

    @Column(name = "min_order_count")
    private Integer minOrderCount;

    @Column(name = "min_avg_order_value", precision = 19, scale = 4)
    private BigDecimal minAvgOrderValue;

    /** Bought at least once in the last N days. */
    @Column(name = "purchased_within_days")
    private Integer purchasedWithinDays;

    /** Has not bought for N days, the lapsed-customer case. */
    @Column(name = "inactive_for_days")
    private Integer inactiveForDays;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "member_count", nullable = false)
    @Builder.Default
    private int memberCount = 0;

    @Column(name = "last_evaluated_at")
    private LocalDateTime lastEvaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** True when no threshold is set, such a segment would match the entire customer list. */
    public boolean hasNoRules() {
        return minTotalSpend == null && minOrderCount == null && minAvgOrderValue == null
                && purchasedWithinDays == null && inactiveForDays == null;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
