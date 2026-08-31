package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cash_drops")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CashDrop extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable: a drop recorded outside a shift (e.g. an owner banking
    // already-collected cash after every shift for the day is closed) has no
    // shift to attach to. Such drops are pure record-keeping — they never
    // reduce any CashShift's totalCashDrops, unlike an in-shift drop.
    private Long shiftId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long cashierUserId;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 255)
    private String reason;

    // Which bank account this cash actually went to — optional, since some
    // drops go to a safe/petty cash box rather than straight to a bank.
    private Long bankAccountId;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
