package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "order_returns",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"return_no"})
        },
        indexes = {
                @Index(name = "idx_return_original_order", columnList = "original_order_id"),
                @Index(name = "idx_return_branch",         columnList = "branch_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderReturn extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // e.g. RTN-2026-06-B1-000001  (mirrors INV-... pattern)
    @Column(name = "return_no", nullable = false, length = 60)
    private String returnNo;

    @Column(name = "original_order_id", nullable = false)
    private Long originalOrderId;

    // Denormalised for display even if original order data changes
    @Column(name = "original_invoice_no", nullable = false, length = 40)
    private String originalInvoiceNo;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    // NULL = walk-in customer
    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status;

    // CASH / BANK / CARD / STORE_CREDIT
    @Column(name = "refund_method", nullable = false, length = 30)
    private String refundMethod;

    @Column(name = "total_refund_amount", nullable = false)
    private double totalRefundAmount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "cashier_note", length = 500)
    private String cashierNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Points this return took back — what the returned goods had earned. */
    @Column(name = "loyalty_points_taken_back", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private int loyaltyPointsTakenBack = 0;

    /**
     * Points handed back to the customer. Only a full return does this: it undoes the sale, so
     * points spent on it are returned. A partial return leaves them alone — the customer paid
     * with them and is keeping some of the goods.
     */
    @Column(name = "loyalty_points_given_back", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private int loyaltyPointsGivenBack = 0;

    /** The balance this return left, so a reprint agrees with the slip it copies. */
    @Column(name = "loyalty_points_balance", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private int loyaltyPointsBalance = 0;

    /**
     * How much of the returned goods' value had been paid in points — the figure that turns
     * "goods returned 1,180" into "refund 200" on the slip. The points count above does not
     * do that job at any rate other than one rupee a point.
     */
    @Column(name = "loyalty_value_returned", nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private java.math.BigDecimal loyaltyValueReturned = java.math.BigDecimal.ZERO;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status   == null) status     = ReturnStatus.COMPLETED;
    }
}
