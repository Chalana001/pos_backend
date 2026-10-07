package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_payments_order", columnNames = "order_id")
}, indexes = {
        @Index(name = "idx_order_payments_order", columnList = "order_id"),
        @Index(name = "idx_order_payments_branch", columnList = "branch_id"),
        @Index(name = "idx_order_payments_method", columnList = "payment_method")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderPayment extends TenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(nullable = false)
    private double amount;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "tendered_amount")
    private Double tenderedAmount;

    @Column(name = "change_amount")
    private Double changeAmount;

    @Column(name = "shortfall_amount")
    private Double shortfallAmount;

    @Column(name = "bank_reference", length = 120)
    private String bankReference;

    @Column(length = 255)
    private String note;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;
    @Column(name = "legacy_backfilled", nullable = false)
    private boolean legacyBackfilled;


    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (paidAt == null) {
            paidAt = createdAt;
        }
    }
}
