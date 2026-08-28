package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A discount that can be applied when a shop is onboarded, renewed or moved between plans.
 *
 * <p>Validity is deliberately several independent conditions rather than one status column —
 * a code can be inside its date window but out of uses, or have uses left but be switched
 * off. {@link #rejectionReason()} says which, so the panel can explain a refusal instead of
 * just saying "invalid".
 */
@Entity
@Table(name = "discount_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountCode {

    public enum DiscountType { PERCENT, FIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored uppercase; matching is case-insensitive because operators type it by hand. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType;

    /**
     * Percentage points for PERCENT, currency for FIXED.
     *
     * <p>The column is {@code discount_amount_value}, not {@code value}: the latter is a
     * reserved word in H2, which the test profile uses, so the table failed to create there.
     */
    @Column(name = "discount_amount_value", nullable = false)
    private double value;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /** Null means unlimited. */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    @Builder.Default
    private int usedCount = 0;

    /** Comma-separated plan ids, or null for any plan. */
    @Column(name = "applies_to_plan_ids", length = 255)
    private String appliesToPlanIds;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Set<Long> planIdSet() {
        if (appliesToPlanIds == null || appliesToPlanIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(appliesToPlanIds.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    /** Why this code cannot be used right now, or null when it can. */
    public String rejectionReason() {
        if (!active) {
            return "This code has been switched off.";
        }
        LocalDateTime now = LocalDateTime.now();
        if (validFrom != null && validFrom.isAfter(now)) {
            return "This code is not valid until " + validFrom.toLocalDate() + ".";
        }
        if (validUntil != null && validUntil.isBefore(now)) {
            return "This code expired on " + validUntil.toLocalDate() + ".";
        }
        if (maxUses != null && usedCount >= maxUses) {
            return "This code has been used its maximum " + maxUses + " time(s).";
        }
        return null;
    }

    public boolean isUsable() {
        return rejectionReason() == null;
    }

    public boolean appliesToPlan(Long planId) {
        Set<Long> plans = planIdSet();
        return plans.isEmpty() || (planId != null && plans.contains(planId));
    }

    /** Never returns more than the gross — a discount cannot make a bill negative. */
    public double amountOff(double gross) {
        double off = discountType == DiscountType.PERCENT ? gross * (value / 100.0) : value;
        return Math.min(Math.max(off, 0), gross);
    }
}
