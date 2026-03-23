package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false)
    private int qty;

    @Column(name = "cost_price", nullable = false)
    private double costPrice;

    @Column(name = "unit_price", nullable = false)
    private double unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private double discountValue;

    @Column(name = "final_unit_price", nullable = false)
    private double finalUnitPrice;

    @Column(name = "line_total", nullable = false)
    private double lineTotal;
}