package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A database-backed payment method. Behavior is carried by the flags
 * (includeInExpectedCash, referencePolicy, allowsCredit, category), never by
 * the displayName, which an admin may rename freely. `code` is the stable,
 * immutable identifier stored on orders/order_payments; renaming the display
 * name never touches historical transactions.
 */
@Entity
@Table(
        name = "payment_methods",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_methods_code", columnNames = {"code"})
        },
        indexes = {
                @Index(name = "idx_payment_methods_active", columnList = "active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMethod extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_policy", nullable = false, length = 10)
    private ReferencePolicy referencePolicy;

    @Column(name = "reference_label", length = 60)
    private String referenceLabel;

    @Column(name = "include_in_expected_cash", nullable = false)
    private boolean includeInExpectedCash;

    @Column(name = "allows_credit", nullable = false)
    private boolean allowsCredit;

    @Column(name = "icon_key", length = 60)
    private String iconKey;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
