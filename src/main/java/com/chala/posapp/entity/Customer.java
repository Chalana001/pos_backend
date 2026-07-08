package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

// MISS-06: Auto-filter deleted customers from all JPA queries
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(
        name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"phone"})
        },
        indexes = {
                @Index(name = "idx_customer_phone", columnList = "phone"),
                @Index(name = "idx_customer_active", columnList = "active"),
                @Index(name = "idx_customer_due", columnList = "due_amount")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Customer extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking — prevents two concurrent credit payments from both
     * reading the same dueAmount and causing it to go negative (lost-update bug).
     */
    @Version
    private Long version;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 255)
    private String address;

    @Column(name = "due_amount", nullable = false)
    private double dueAmount;

    private Double creditLimit;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;

    /** MISS-06: Soft-delete timestamp — null means not deleted. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        active = true;
        if (dueAmount == 0) dueAmount = 0;
    }
}