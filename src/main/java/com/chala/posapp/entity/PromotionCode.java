package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A code a customer presents at the till to unlock a promotion.
 *
 * <p>A promotion with any codes is code-gated: it applies only when one of them is presented.
 * Validity is several independent conditions rather than one status, a code can be inside its
 * dates but out of uses, or have uses left but be switched off, and {@link #rejectionReason}
 * says which, so the till can explain a refusal instead of saying "invalid". Same shape as the
 * SaaS {@code DiscountCode}, which got this right first.
 *
 * <p>{@code redemptionsUsed} is maintained by a conditional UPDATE inside the order transaction
 * and is not written by entity saves, so an admin edit cannot clobber a count a sale just bumped.
 */
@Entity
@Table(
        name = "promotion_codes",
        indexes = {
                @Index(name = "idx_promotion_code_promotion", columnList = "promotion_id"),
                @Index(name = "idx_promotion_code_batch", columnList = "batch_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_promotion_code", columnNames = "code")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionCode extends TenantEntity {

    public enum CodeType { PUBLIC, SINGLE_USE, PER_CUSTOMER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    /** Stored uppercase; matching is case-insensitive because operators type it by hand. */
    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_type", nullable = false, length = 16)
    @Builder.Default
    private CodeType codeType = CodeType.PUBLIC;

    /** Null means unlimited. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "redemptions_used", nullable = false, insertable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private int redemptionsUsed;

    /** Null means unlimited per customer. */
    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Groups codes generated together so a campaign's codes can be exported or retired as a set. */
    @Column(name = "batch_id", length = 40)
    private String batchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (code != null) {
            code = code.trim().toUpperCase();
        }
    }

    public boolean isExhausted() {
        return maxRedemptions != null && redemptionsUsed >= maxRedemptions;
    }

    /**
     * Why this code cannot be used right now, or null if it can. Ordered so the most
     * permanent reason wins: a switched-off code is off whatever the date says.
     */
    public String rejectionReason(LocalDateTime now) {
        if (!active) {
            return "This code has been switched off";
        }
        if (validFrom != null && now.isBefore(validFrom)) {
            return "This code is not valid yet";
        }
        if (validTo != null && now.isAfter(validTo)) {
            return "This code has expired";
        }
        if (isExhausted()) {
            return "This code has been fully redeemed";
        }
        return null;
    }
}
