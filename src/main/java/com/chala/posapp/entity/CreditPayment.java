package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "credit_payments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CreditPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long cashierUserId;

    @Column(nullable = false)
    private double amount;

    @Column(length = 255)
    private String note;

    private LocalDateTime paidAt;

    @PrePersist
    void onCreate() {
        paidAt = LocalDateTime.now();
    }
}
