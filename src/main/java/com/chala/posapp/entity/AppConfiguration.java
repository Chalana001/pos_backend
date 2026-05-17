package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_configurations",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id"})
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

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
