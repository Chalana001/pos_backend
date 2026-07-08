package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "pending_order_items",
        indexes = {
                @Index(name = "idx_pending_order_items", columnList = "pending_order_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingOrderItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pending_order_id", nullable = false)
    private Long pendingOrderId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;

    @Column(name = "unit_price", nullable = false)
    private double unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private double discountValue;

    @Column(name = "warranty_label", length = 120)
    private String warrantyLabel;

    @Column(name = "warranty_period_value")
    private Integer warrantyPeriodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "warranty_period_unit", length = 20)
    private WarrantyPeriodUnit warrantyPeriodUnit;
}
