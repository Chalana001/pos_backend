package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_databases", uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true, length = 100)
    private String tenantId;

    @Column(name = "db_name", nullable = false, length = 120)
    private String dbName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MigrationStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "migrated_at")
    private LocalDateTime migratedAt;

    public enum MigrationStatus {
        PENDING, COPIED, MIGRATED, FAILED
    }
}
