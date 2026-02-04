package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_adjustments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockAdjustmentType type;

    @Column(nullable = false)
    private int qtyChange;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(nullable = false)
    private Long userId;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
