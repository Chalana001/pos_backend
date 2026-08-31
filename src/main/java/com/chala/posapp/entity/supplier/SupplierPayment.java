package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.CashSource;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "supplier_payments",
        indexes = {
                @Index(name = "idx_supplier_payment_supplier", columnList = "supplier_id"),
                @Index(name = "idx_supplier_payment_paid_at", columnList = "paid_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPayment extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    private Purchase purchase;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 40)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "cash_source", nullable = false, length = 40)
    private CashSource cashSource;

    @Column(name = "cash_shift_id")
    private Long cashShiftId;

    @Column(name = "cashier_user_id")
    private Long cashierUserId;

    @Column(name = "cash_source_branch_id")
    private Long cashSourceBranchId;

    @Column(length = 255)
    private String note;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    private Long createdByUserId;

    @PrePersist
    void onCreate() {
        if (paidAt == null) paidAt = LocalDateTime.now();
        if (paymentMethod == null || paymentMethod.isBlank()) paymentMethod = "CASH";
        if (cashSource == null) cashSource = CashSource.BRANCH_CASH;
    }
}
