package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stock_transfers", indexes = {
        @Index(name = "idx_transfer_no", columnList = "transferNo"), // Search by No
        @Index(name = "idx_transfer_branches", columnList = "fromBranchId, toBranchId"), // Filter by Branch
        @Index(name = "idx_transfer_status", columnList = "status") // Filter by Status (Pending/Completed)
})
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


    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime receivedAt;
    private LocalDateTime canceledAt;

    @OneToMany(mappedBy = "transferId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StockTransferItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        requestedAt = LocalDateTime.now();
        if (status == null) status = StockTransferStatus.IN_TRANSIT;
    }
}