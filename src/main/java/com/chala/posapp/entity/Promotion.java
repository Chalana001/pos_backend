package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "promotions",
        indexes = {
                @Index(name = "idx_promotion_active_dates", columnList = "active, start_at, end_at"),
                @Index(name = "idx_promotion_live", columnList = "active, deleted_at, start_at, end_at"),
                @Index(name = "idx_promotion_branch", columnList = "branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromotionScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    // Money is DECIMAL(19,4) from V35. These were double, which stores 0.1 as
    // 0.1000000000000000055511151231257827 and then rounds on the way out; the engine now
    // computes in BigDecimal end to end, so the columns had to stop being the weak link.
    @Column(name = "discount_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountValue;

    @Column(name = "min_bill_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal minBillAmount = BigDecimal.ZERO;

    @Column(name = "max_discount_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal maxDiscountAmount = BigDecimal.ZERO;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private int priority = 0;

    /**
     * Minimum gross margin, as a percentage of the discounted price, that a line must keep for
     * this promotion to apply. Null means no floor.
     *
     * <p>Per-item pricing makes selling below cost easy — the price is typed straight in with
     * nothing to compare it against — so this is the guard that catches 39.90 entered for
     * 399.00 before it reaches a till.
     */
    @Column(name = "margin_floor_percent", precision = 5, scale = 2)
    private BigDecimal marginFloorPercent;

    /**
     * Lets this promotion price below cost deliberately. A loss leader is a legitimate
     * campaign; the flag is how an operator says so, and it is why below-cost pricing is
     * refused rather than silently allowed by default.
     */
    @Column(name = "allow_below_cost", nullable = false)
    @Builder.Default
    private boolean allowBelowCost = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Set when the promotion is retired. The row is never removed: past orders carry
     * {@code promotion_id} with no foreign key behind it, so deleting the row would leave
     * the discounts on the books while the terms that produced them vanished. Every read
     * path filters {@code deleted_at IS NULL}; reporting joins ignore it and still resolve
     * the name and discount of a campaign that ended.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PromotionTarget> targets = new ArrayList<>();

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
