package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cash_shifts")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CashShift {

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

    private Double countedCash;
    private Double expectedCash;
    private Double cashDifference;

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
    }
}
