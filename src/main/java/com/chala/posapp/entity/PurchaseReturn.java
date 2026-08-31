package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "purchase_returns",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"debit_note_no"})
        },
        indexes = {
                @Index(name = "idx_pr_purchase_id", columnList = "purchase_id"),
                @Index(name = "idx_pr_supplier_id", columnList = "supplier_id"),
                @Index(name = "idx_pr_grn_id",      columnList = "grn_id"),
                @Index(name = "idx_pr_branch_id",   columnList = "branch_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseReturn extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // e.g. DBN-42-R1  (Debit Note number)
    @Column(name = "debit_note_no", nullable = false, length = 60)
    private String debitNoteNo;

    @Column(name = "purchase_id", nullable = false)
    private Long purchaseId;

    // Denormalised — supplier invoice no for display
    @Column(name = "purchase_invoice_no", nullable = false, length = 100)
    private String purchaseInvoiceNo;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    // Which GRN the returned items belong to
    @Column(name = "grn_id", nullable = false)
    private Long grnId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "processed_by_user_id", nullable = false)
    private Long processedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status;

    @Column(name = "total_return_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalReturnAmount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status    == null) status     = ReturnStatus.COMPLETED;
        if (totalReturnAmount == null) totalReturnAmount = BigDecimal.ZERO;
    }
}
