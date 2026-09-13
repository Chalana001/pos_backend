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

    /** The branch this campaign runs at. Required since V50. There is no "every branch". */
    @Column(name = "branch_id", nullable = false)
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
     * <p>Per-item pricing makes selling below cost easy, the price is typed straight in with
     * nothing to compare it against, so this is the guard that catches 39.90 entered for
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

    /** How this promotion discounts. Defaults to the original rate-off-list behaviour. */
    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 24)
    @Builder.Default
    private PromotionEffectType effectType = PromotionEffectType.DISCOUNT;

    /** BUY_X_GET_Y_FREE: units to buy. BUNDLE / CHEAPEST_FREE: units per group. Primary units. */
    @Column(name = "buy_qty", precision = 12, scale = 3)
    private BigDecimal buyQty;

    /** BUY_X_GET_Y_FREE: units given free per group. */
    @Column(name = "get_qty", precision = 12, scale = 3)
    private BigDecimal getQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "stacking_mode", nullable = false, length = 16)
    @Builder.Default
    private StackingMode stackingMode = StackingMode.BEST_ONLY;

    /** When off, a cashier's line discount does not stack on top of this promotion. */
    @Column(name = "allow_manual_stacking", nullable = false)
    @Builder.Default
    private boolean allowManualStacking = true;

    /**
     * Optimistic lock. Two managers editing the same promotion used to overwrite each other
     * silently; now the second save fails and has to be redone against the current row.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<PromotionTier> tiers = new ArrayList<>();

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<PromotionSchedule> schedules = new ArrayList<>();

    /** A promotion with any codes applies only when one of them is presented. */
    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<PromotionCode> codes = new ArrayList<>();

    /** Null means unlimited. Counts orders, not lines. */
    @Column(name = "max_total_redemptions")
    private Integer maxTotalRedemptions;

    @Column(name = "max_redemptions_per_customer")
    private Integer maxRedemptionsPerCustomer;

    /** Total discount this promotion may give before it stops applying. Null means unlimited. */
    @Column(name = "budget_amount", precision = 19, scale = 4)
    private BigDecimal budgetAmount;

    // Running counters. Maintained by conditional UPDATEs inside the order transaction and
    // deliberately not written by entity saves, so an admin editing the promotion's name cannot
    // overwrite a count a sale bumped a second ago.
    // ColumnDefault matters wherever Hibernate generates the schema (the test profile): a
    // NOT NULL column that inserts omit needs a default, or the first save fails.
    @Column(name = "times_redeemed", nullable = false, insertable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private int timesRedeemed;

    @Column(name = "budget_consumed", nullable = false, insertable = false, updatable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal budgetConsumed;

    /**
     * The stored half of the lifecycle. {@code active} stays the engine's switch and is kept in
     * step with this by {@code PromotionLifecycleService}; live/scheduled/ended/exhausted are
     * derived from dates and counters at read time.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PromotionStatus status = PromotionStatus.ACTIVE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** Who sent it for approval; the approver must be someone else. */
    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approval_note", length = 255)
    private String approvalNote;

    public boolean isCodeGated() {
        return codes != null && !codes.isEmpty();
    }

    public boolean isExhausted() {
        if (maxTotalRedemptions != null && timesRedeemed >= maxTotalRedemptions) {
            return true;
        }
        return budgetAmount != null && budgetConsumed != null && budgetConsumed.compareTo(budgetAmount) >= 0;
    }

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
