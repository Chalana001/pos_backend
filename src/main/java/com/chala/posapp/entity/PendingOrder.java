package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "pending_orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "table_id"})
        },
        indexes = {
                @Index(name = "idx_tenant_branch_pending_order", columnList = "tenant_id, branch_id"),
                @Index(name = "idx_tenant_table_pending_order", columnList = "tenant_id, table_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingOrder extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "bill_discount", nullable = false)
    @Builder.Default
    private double billDiscount = 0.0;

    @Column(nullable = false)
    @Builder.Default
    private double subTotal = 0.0;

    @Column(name = "grand_total", nullable = false)
    @Builder.Default
    private double grandTotal = 0.0;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
