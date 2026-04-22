package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "branches",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "code"}),
                @UniqueConstraint(columnNames = {"tenant_id", "name"})
        },
        indexes = {
                @Index(name = "idx_tenant_branch_code", columnList = "tenant_id, code")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Branch extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(length = 30)
    private String phone;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String logo;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        // ඩෙෆෝල්ට් ඇක්ටිව් කරන්න ලොජික් එකක්
        if (!active) active = true;
    }
}
