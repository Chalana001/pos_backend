package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per tenant: how careful this shop wants to be with promotions.
 *
 * <p>Approval is optional because a single-owner shop has nobody to approve to. When on, a
 * promotion whose deepest cut reaches {@code approvalThresholdPercent}, or whose flat amount
 * reaches {@code approvalThresholdAmount}, waits in PENDING_APPROVAL for an admin other than
 * the person who submitted it.
 */
@Entity
@Table(name = "promotion_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionSettings extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_required", nullable = false)
    @Builder.Default
    private boolean approvalRequired = false;

    @Column(name = "approval_threshold_percent", precision = 5, scale = 2)
    private BigDecimal approvalThresholdPercent;

    @Column(name = "approval_threshold_amount", precision = 19, scale = 4)
    private BigDecimal approvalThresholdAmount;

    /** When off, only a manager or admin may add a line discount on top of a promotion. */
    @Column(name = "cashier_manual_stacking", nullable = false)
    @Builder.Default
    private boolean cashierManualStacking = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static PromotionSettings defaults() {
        return PromotionSettings.builder().build();
    }
}
