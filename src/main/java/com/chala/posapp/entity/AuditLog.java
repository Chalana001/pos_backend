package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * MISS-03: Immutable audit log entry written via Spring AOP on every sensitive
 * write operation (order creation, user creation, password reset, expense, GRN, etc.).
 *
 * TABLE: audit_logs — created in V12 migration.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor_ts",  columnList = "actor_user_id, performed_at"),
        @Index(name = "idx_audit_entity",    columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_action_ts", columnList = "action, performed_at")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Username of the actor (from JWT / SecurityContext). */
    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    /** User ID of the actor. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Role of the actor at time of action. */
    @Column(name = "actor_role", length = 30)
    private String actorRole;

    /** e.g. ORDER, EXPENSE, USER, GRN, STOCK_ADJUSTMENT */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** ID of the affected row. */
    @Column(name = "entity_id")
    private Long entityId;

    /** e.g. CREATE, UPDATE, DELETE, CANCEL, RESET_PASSWORD */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /** Branch scope of the operation. */
    @Column(name = "branch_id")
    private Long branchId;

    /** Brief human-readable summary — always safe to log (no PII). */
    @Column(name = "summary", length = 500)
    private String summary;

    /** Client IP address, resolved from X-Forwarded-For or remoteAddr. */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @PrePersist
    void onPersist() {
        if (performedAt == null) performedAt = LocalDateTime.now();
    }
}
