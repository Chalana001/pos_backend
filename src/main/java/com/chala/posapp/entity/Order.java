package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"invoice_no"}),
                @UniqueConstraint(columnNames = {"client_sale_id"})
        },
        indexes = {
                @Index(name = "idx_invoice", columnList = "invoice_no"),
                @Index(name = "idx_branch_order", columnList = "branch_id"),
                @Index(name = "idx_client_sale", columnList = "client_sale_id")
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

    @Column(name = "client_sale_id", length = 100)
    private String clientSaleId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "receipt_branch_name", length = 120)
    private String receiptBranchName;

    @Column(name = "receipt_branch_address", length = 255)
    private String receiptBranchAddress;

    @Column(name = "receipt_branch_phone", length = 30)
    private String receiptBranchPhone;

    @Lob
    @Column(name = "receipt_branch_logo", columnDefinition = "LONGTEXT")
    private String receiptBranchLogo;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType orderType;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_mode", nullable = false, length = 20)
    @Builder.Default
    private SaleMode saleMode = SaleMode.TAKEAWAY;

    @Column(name = "table_id")
    private Long tableId;

    @Column(name = "table_name", length = 120)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private double subTotal;

    @Column(nullable = false)
    private double billDiscount;

    @Column(name = "promotion_discount_total", nullable = false)
    @Builder.Default
    private double promotionDiscountTotal = 0.0;

    @Column(name = "bill_promotion_id")
    private Long billPromotionId;

    @Column(name = "bill_promotion_name", length = 120)
    private String billPromotionName;

    @Column(name = "bill_promotion_discount_amount", nullable = false)
    @Builder.Default
    private double billPromotionDiscountAmount = 0.0;

    @Column(nullable = false)
    private double grandTotal;

    @Column(nullable = false)
    private double paidAmount;

    @Column(nullable = false)
    private double dueAmount;

    @Column(name = "sale_paid_amount")
    private Double salePaidAmount;

    @Column(name = "sale_due_amount")
    private Double saleDueAmount;

    private String note;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * The trading day this sale belongs to, derived from createdAt on persist.
     * Reporting keys off this rather than off createdAt so that an offline sale
     * imported days later still lands on the day it was actually made.
     */
    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "offline_sold_at")
    private LocalDateTime offlineSoldAt;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "offline_imported", nullable = false)
    @Builder.Default
    private boolean offlineImported = false;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (businessDate == null) businessDate = createdAt.toLocalDate();
        if (status == null) status = OrderStatus.COMPLETED;
    }
}
