package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "expense_types",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "name"})
        },
        indexes = {
                @Index(name = "idx_tenant_expense_type_name", columnList = "tenant_id, name"),
                @Index(name = "idx_tenant_expense_type_active", columnList = "tenant_id, active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseType extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "count_in_profit_report", nullable = false)
    private boolean countInProfitReport;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
