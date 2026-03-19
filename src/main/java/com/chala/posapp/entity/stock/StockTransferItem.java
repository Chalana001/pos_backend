package com.chala.posapp.entity.stock;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stock_transfer_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long transferId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false)
    private int qty;
}