package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stock_override_audits",
        indexes = {
                @Index(name = "idx_tenant_stock_override_order", columnList = "tenant_id, order_id"),
                @Index(name = "idx_tenant_stock_override_stock_item", columnList = "tenant_id, stock_item_id"),
                @Index(name = "idx_tenant_stock_override_created", columnList = "tenant_id, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockOverrideAudit extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "sale_item_id", nullable = false)
    private Long saleItemId;

    @Column(name = "sale_item_name", nullable = false, length = 160)
    private String saleItemName;

    @Column(name = "stock_item_id", nullable = false)
    private Long stockItemId;

    @Column(name = "stock_item_name", nullable = false, length = 160)
    private String stockItemName;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "required_quantity", nullable = false)
    private Integer requiredQuantity;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Column(name = "shortage_quantity", nullable = false)
    private Integer shortageQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_mode", nullable = false, length = 30)
    private StockOverrideMode overrideMode;

    @Column(name = "override_user_id", nullable = false)
    private Long overrideUserId;

    @Column(name = "override_username", nullable = false, length = 80)
    private String overrideUsername;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
