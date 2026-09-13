package com.chala.posapp.entity;

import com.chala.posapp.entity.supplier.Supplier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
// No uniqueConstraints here any more. (supplier_id, invoice_no) used to be unique across
// every row, which made a supersede impossible: the cancelled original keeps the supplier's
// real invoice number, it is the number printed on the paper, so the replacement
// collided with it. V47 replaces that with a unique index over COMPLETED rows only, via a
// generated column MySQL can index and leave NULL for cancelled bills.
//
// The database still enforces it, just more narrowly; PurchaseService also checks, because
// a constraint violation is not a message anyone can act on, and under the test profile
// Hibernate builds this schema from the annotations and never sees V47 at all.
@Table(name = "purchase")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Purchase extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    private LocalDateTime createdAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal grandTotal;

    @Column(precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "cash_source", nullable = false, length = 40)
    private CashSource cashSource;

    @Column(name = "cash_shift_id")
    private Long cashShiftId;

    @Column(name = "cashier_user_id")
    private Long cashierUserId;

    @Column(name = "cash_source_amount", precision = 12, scale = 2)
    private BigDecimal cashSourceAmount;

    @Column(name = "cash_source_branch_id")
    private Long cashSourceBranchId;

    @Column(precision = 12, scale = 2)
    private BigDecimal dueAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "ENUM('CANCELED','COMPLETED') DEFAULT 'COMPLETED'")
    private PurchaseStatus status;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    /** Who voided it. Null for bills cancelled before V48. Nothing honest to backfill. */
    @Column(name = "canceled_by_user_id")
    private Long canceledByUserId;

    /** The bill this one was created to correct, if any. Set on the replacement. */
    @Column(name = "replaces_purchase_id")
    private Long replacesPurchaseId;

    /** The bill that corrected this one, if any. Set on the original when it is superseded. */
    @Column(name = "replaced_by_purchase_id")
    private Long replacedByPurchaseId;

    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL)
    @Builder.Default
    private List<GRN> grnList = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (grandTotal == null) grandTotal = BigDecimal.ZERO;
        if (discountAmount == null) discountAmount = BigDecimal.ZERO;
        if (paidAmount == null) paidAmount = BigDecimal.ZERO;
        if (cashSourceAmount == null) cashSourceAmount = paidAmount;
        if (cashSource == null) cashSource = paidAmount.compareTo(BigDecimal.ZERO) > 0 ? CashSource.BRANCH_CASH : CashSource.NONE;
        if (dueAmount == null) dueAmount = BigDecimal.ZERO;
        if (status == null) status = PurchaseStatus.COMPLETED;
    }
}
