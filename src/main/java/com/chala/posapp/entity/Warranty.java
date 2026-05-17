package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "warranties",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "warranty_no"})
        },
        indexes = {
                @Index(name = "idx_tenant_warranty_lookup", columnList = "tenant_id, warranty_no"),
                @Index(name = "idx_tenant_warranty_order", columnList = "tenant_id, order_id"),
                @Index(name = "idx_tenant_warranty_branch", columnList = "tenant_id, branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Warranty extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warranty_no", nullable = false, length = 80)
    private String warrantyNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(name = "warranty_label", nullable = false, length = 120)
    private String warrantyLabel;

    @Column(name = "period_value", nullable = false)
    private int periodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_unit", nullable = false, length = 20)
    private WarrantyPeriodUnit periodUnit;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WarrantyStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = WarrantyStatus.ACTIVE;
        }
    }
}
