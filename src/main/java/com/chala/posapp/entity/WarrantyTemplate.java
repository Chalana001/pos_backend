package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "warranty_templates",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"label"})
        },
        indexes = {
                @Index(name = "idx_warranty_templates_active", columnList = "active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarrantyTemplate extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "period_value", nullable = false)
    private int periodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_unit", nullable = false, length = 20)
    private WarrantyPeriodUnit periodUnit;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
