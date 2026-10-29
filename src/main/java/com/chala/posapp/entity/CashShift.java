package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cash_shifts")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CashShift extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long cashierUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShiftStatus status;

    @Column(nullable = false)
    private double openingCash;

    @Column(nullable = false)
    private double totalExpenses;

    @Column(nullable = false)
    private double totalCashDrops;

    @Column(nullable = false)
    private Double cashSales;

    /**
     * Card and bank tender taken during this shift, and cash received settling
     * credit dues from earlier sales. Informational buckets: only cashSales,
     * expenses and drops feed Expected Cash. Nullable in the DB (V60 backfills
     * historical rows to 0) but always non-null in memory, so readers never
     * have to null-check.
     */
    @Column
    private Double cardSales;
    @Column
    private Double bankSales;
    @Column
    private Double cashCollections;

    private Double countedCash;
    private Double expectedCash;
    private Double cashDifference;

    @Column(name = "counted_cash_breakdown", columnDefinition = "LONGTEXT")
    private String countedCashBreakdown;

    private String openNote;
    private String closeNote;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;

    @PrePersist
    void onCreate() {
        openedAt = LocalDateTime.now();
        if (status == null) status = ShiftStatus.OPEN;
        totalExpenses = 0;
        totalCashDrops = 0;
        cashSales = 0.0;
        if (cardSales == null) cardSales = 0.0;
        if (bankSales == null) bankSales = 0.0;
        if (cashCollections == null) cashCollections = 0.0;
    }
}
