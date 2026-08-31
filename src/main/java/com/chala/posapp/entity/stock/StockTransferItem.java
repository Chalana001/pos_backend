package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "stock_transfer_items",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"transfer_id", "batch_id"})
        },
        indexes = {
                @Index(name = "idx_transfer_items_fast", columnList = "transfer_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockTransferItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

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

    @Column(name = "display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;
}
