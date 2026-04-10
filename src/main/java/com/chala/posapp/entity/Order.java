package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "invoice_no"})
        },
        indexes = {
                @Index(name = "idx_tenant_invoice", columnList = "tenant_id, invoice_no"),
                @Index(name = "idx_tenant_branch_order", columnList = "tenant_id, branch_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Order extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "receipt_branch_name", length = 120)
    private String receiptBranchName;

    @Column(name = "receipt_branch_address", length = 255)
    private String receiptBranchAddress;

    @Column(name = "receipt_branch_phone", length = 30)
    private String receiptBranchPhone;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private double subTotal;

    @Column(nullable = false)
    private double billDiscount;

    @Column(nullable = false)
    private double grandTotal;

    @Column(nullable = false)
    private double paidAmount;

    @Column(nullable = false)
    private double dueAmount;

    private String note;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = OrderStatus.COMPLETED;
    }
}
