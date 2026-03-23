package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "phone"})
        },
        indexes = {
                @Index(name = "idx_tenant_customer_phone", columnList = "tenant_id, phone")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Customer extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 255)
    private String address;

    @Column(nullable = false)
    private double dueAmount;

    private Double creditLimit;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        active = true; // මෙතන පොඩි logic එකක් දැම්මා
        if (dueAmount == 0) dueAmount = 0; // Default 0
    }
}