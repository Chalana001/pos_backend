package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "order_return_items",
        indexes = {
                @Index(name = "idx_return_items_return_id",  columnList = "order_return_id"),
                @Index(name = "idx_return_items_order_item", columnList = "order_item_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderReturnItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_return_id", nullable = false)
    private Long orderReturnId;

    // FK to original order_items.id — used for "already returned qty" checks
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false, length = 80)
    private String barcode;

    // Quantity being returned, in same base units as OrderItem.qty
    @Column(name = "return_qty", nullable = false)
    private int returnQty;

    @Column(name = "unit_price", nullable = false)
    private double unitPrice;

    @Column(name = "final_unit_price", nullable = false)
    private double finalUnitPrice;

    // returnQty × finalUnitPrice
    @Column(name = "refund_line_amount", nullable = false)
    private double refundLineAmount;

    // false only if item is SERVICE/RECIPE or offline-legacy with no stock rows
    @Column(name = "stock_reversed", nullable = false)
    @Builder.Default
    private boolean stockReversed = true;
}
