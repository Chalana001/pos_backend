package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Append-only record of everything a super admin does to a shop.
 *
 * <p>Lives in the control-plane database, not in any tenant database, so blocking or deleting a
 * shop never takes its history with it. Nothing updates or deletes these rows.
 */
@Entity
@Table(name = "super_admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Username of the super admin who performed the action. */
    @Column(nullable = false, length = 80)
    private String actor;

    /** Machine-readable verb, e.g. {@code MODULE_ENABLED}, {@code SHOP_BLOCKED}. */
    @Column(nullable = false, length = 60)
    private String action;

    /** {@code SHOP}, {@code PLAN}, {@code MODULE} or {@code SYSTEM}. */
    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    /** Tenant id, plan id or module key depending on {@link #targetType}. */
    @Column(name = "target_id", length = 120)
    private String targetId;

    /** One line rendered directly in the panel timeline. */
    @Column(nullable = false, length = 500)
    private String summary;

    /** Optional JSON blob with before/after values. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
