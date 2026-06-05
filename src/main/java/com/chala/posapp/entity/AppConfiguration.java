package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_configurations",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "branch_id"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppConfiguration extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long branchId;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean recipeItemsEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean weightItemsEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean servicesEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean tableManagementEnabled;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean dineInEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'MAIN_AND_SUB'")
    @Builder.Default
    private CategoryMode categoryMode = CategoryMode.MAIN_AND_SUB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30) default 'MANAGER_OVERRIDE'")
    @Builder.Default
    private StockOverrideMode stockOverrideMode = StockOverrideMode.MANAGER_OVERRIDE;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean adminStockOverrideAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean managerStockOverrideAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean cashierStockOverrideAllowed = false;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean warrantyEnabled = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean kotEnabled = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean printReceiptAfterCheckout = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean adminWarrantyAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean managerWarrantyAllowed = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean cashierWarrantyAllowed = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (categoryMode == null) {
            categoryMode = CategoryMode.MAIN_AND_SUB;
        }
        if (stockOverrideMode == null) {
            stockOverrideMode = StockOverrideMode.MANAGER_OVERRIDE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
