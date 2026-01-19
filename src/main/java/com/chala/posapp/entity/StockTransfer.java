package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_transfers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String transferNo;

    @Column(nullable = false)
    private Long fromBranchId;

    @Column(nullable = false)
    private Long toBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockTransferStatus status;

    @Column(nullable = false)
    private Long requestedByUserId;

    private Long receivedByUserId;

    @Column(length = 255)
    private String note;

    @Column(length = 255)
    private String cancelReason;

    private LocalDateTime requestedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime canceledAt;

    @PrePersist
    void onCreate() {
        requestedAt = LocalDateTime.now();
        if (status == null) status = StockTransferStatus.REQUESTED;
    }
}