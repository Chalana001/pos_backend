package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 80)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private BillingActionType actionType;

    /**
     * What was actually charged, after any discount. Left as the meaning it has always had,
     * so every existing report and SUM stays correct.
     */
    @Column(nullable = false)
    private double amount;

    /** List price before a discount code was applied. Equals {@link #amount} when there was none. */
    @Column(name = "gross_amount", nullable = false)
    @Builder.Default
    private double grossAmount = 0;

    @Column(name = "discount_amount", nullable = false)
    @Builder.Default
    private double discountAmount = 0;

    @Column(name = "discount_code", length = 40)
    private String discountCode;

    @Column(name = "shop_name", nullable = false, length = 120)
    private String shopName;

    @Column(name = "reference_note", length = 255)
    private String referenceNote;

    @Column(name = "performed_by", nullable = false, length = 80)
    private String performedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        // A record written without a gross was not discounted; gross is simply the amount.
        if (grossAmount == 0) {
            grossAmount = amount;
        }
    }
}
