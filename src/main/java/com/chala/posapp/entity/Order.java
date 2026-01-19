package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // for receipt (ex: INV-2026-000001)
    @Column(nullable = false, unique = true, length = 40)
    private String invoiceNo;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long cashierUserId;

    // customerId required for CREDIT
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private double subTotal; // sum of line totals before bill discount

    @Column(nullable = false)
    private double billDiscount; // optional

    @Column(nullable = false)
    private double grandTotal;

    @Column(nullable = false)
    private double paidAmount; // cash sales amount paid now (credit usually 0)

    @Column(nullable = false)
    private double dueAmount; // credit due (grandTotal - paid)

    private String note;

    private String cancelReason;

    private LocalDateTime createdAt;
    private LocalDateTime canceledAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = OrderStatus.COMPLETED;
    }
}
