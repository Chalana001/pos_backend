package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "purchase_return_items",
        indexes = {
                @Index(name = "idx_pri_return_id",   columnList = "purchase_return_id"),
                @Index(name = "idx_pri_grn_item_id", columnList = "grn_item_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseReturnItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_return_id", nullable = false)
    private Long purchaseReturnId;

    @Column(name = "grn_item_id", nullable = false)
    private Long grnItemId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    @Column(length = 80)
    private String barcode;

    @Column(name = "return_qty", nullable = false)
    private int returnQty;

    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "return_line_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnLineAmount;

    // false if item is SERVICE/RECIPE or batch not found
    @Builder.Default
    @Column(name = "stock_deducted", nullable = false)
    private boolean stockDeducted = true;
}
