package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "order_items",
        indexes = {
                @Index(name = "idx_tenant_order_items_lookup", columnList = "tenant_id, order_id"),
                @Index(name = "idx_tenant_item_sales_lookup", columnList = "tenant_id, item_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 20)
    private ItemType itemType;

    @Column(nullable = false)
    private int qty;

    @Column(name = "display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;

    @Column(name = "cost_price", nullable = false)
    private double costPrice;

    @Column(name = "unit_price", nullable = false)
    private double unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private double discountValue;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "promotion_name", length = 120)
    private String promotionName;

    @Column(name = "promotion_discount_amount", nullable = false)
    @Builder.Default
    private double promotionDiscountAmount = 0.0;

    @Column(name = "final_unit_price", nullable = false)
    private double finalUnitPrice;

    @Column(name = "line_cost", nullable = false)
    private double lineCost;

    @Column(name = "line_total", nullable = false)
    private double lineTotal;

    @Column(name = "warranty_label", length = 120)
    private String warrantyLabel;

    @Column(name = "warranty_period_value")
    private Integer warrantyPeriodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "warranty_period_unit", length = 20)
    private WarrantyPeriodUnit warrantyPeriodUnit;
}
