package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "expenses",
        indexes = {
                @Index(name = "idx_branch_shift_exp", columnList = "branch_id, shift_id"),
                @Index(name = "idx_created_at_exp", columnList = "created_at"),
                @Index(name = "idx_expense_type_exp", columnList = "expense_type_id")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Expense extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "cashier_user_id", nullable = false)
    private Long cashierUserId;

    @Column(name = "expense_type_id")
    private Long expenseTypeId;

    @Column(nullable = false, length = 120)
    private String category;

    @Column(name = "count_in_profit_report", nullable = false)
    private boolean countInProfitReport;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
