package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One use of a {@link DiscountCode}: which shop, how much came off, and against what payment. */
@Entity
@Table(name = "discount_redemptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_id", nullable = false)
    private Long codeId;

    /** Denormalised so a deleted code still reads sensibly in the history. */
    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "billing_record_id")
    private Long billingRecordId;

    @Column(name = "gross_amount", nullable = false)
    private double grossAmount;

    @Column(name = "amount_off", nullable = false)
    private double amountOff;

    @Column(name = "net_amount", nullable = false)
    private double netAmount;

    @Column(name = "redeemed_by", length = 80)
    private String redeemedBy;

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private LocalDateTime redeemedAt;

    @PrePersist
    void onCreate() {
        redeemedAt = LocalDateTime.now();
    }
}
