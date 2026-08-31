package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A per-shop override of the plan default for one module.
 *
 * <p>The presence of a row is the override. Deleting it returns the shop to whatever its plan
 * says, which is what the panel's "reset to plan" action does — it does not write an
 * {@code enabled = plan default} row, because then a later plan change would not reach the shop.
 */
@Entity
@Table(name = "tenant_modules",
        uniqueConstraints = @UniqueConstraint(name = "uk_tenant_modules", columnNames = {"tenant_id", "module_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "module_key", nullable = false, length = 60)
    private String moduleKey;

    @Column(nullable = false)
    private boolean enabled;

    /** Why this shop deviates — shown in the panel next to the toggle. */
    @Column(length = 255)
    private String note;

    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
