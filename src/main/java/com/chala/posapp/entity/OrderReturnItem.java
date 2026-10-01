package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

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

    // FK to original order_items.id, used for "already returned qty" checks
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false, length = 80)
    private String barcode;

    // Quantity being returned, in DISPLAY units (same as OrderItem.displayQty).
    // This is what the return slip prints and what the cashier checks against goods on the counter.
    // Stock arithmetic uses a separate normalized quantity that must be derived at runtime.
    @Column(name = "return_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal returnQty;

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
