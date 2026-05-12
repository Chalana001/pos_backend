package com.chala.posapp.entity.supplier;

import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "supplier_payments",
        indexes = {
                @Index(name = "idx_tenant_supplier_payment_supplier", columnList = "tenant_id, supplier_id"),
                @Index(name = "idx_tenant_supplier_payment_paid_at", columnList = "tenant_id, paid_at")
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

    @Column(length = 255)
    private String note;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    private Long createdByUserId;

    @PrePersist
    void onCreate() {
        if (paidAt == null) paidAt = LocalDateTime.now();
        if (paymentMethod == null || paymentMethod.isBlank()) paymentMethod = "CASH";
    }
}
