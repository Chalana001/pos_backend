package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "stock_transfer_items",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "transfer_id", "batch_id"})
        },
        indexes = {
                @Index(name = "idx_tenant_transfer_items_fast", columnList = "tenant_id, transfer_id")
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
}