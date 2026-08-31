package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One support session where an operator opened a shop's own POS.
 *
 * <p>The issued token carries this row's {@link #tokenId} as its {@code jti}. Every request
 * made with that token is checked against this row, so revoking it ends the session
 * immediately rather than waiting for the token to expire — which is the difference between
 * a support tool and a permanent back door.
 */
@Entity
@Table(name = "impersonation_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImpersonationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The token's {@code jti} claim. Random per session, never reused. */
    @Column(name = "token_id", nullable = false, unique = true, length = 64)
    private String tokenId;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    /** The super admin who opened the session. */
    @Column(nullable = false, length = 80)
    private String actor;

    /** The shop user whose identity was borrowed. */
    @Column(name = "target_username", nullable = false, length = 80)
    private String targetUsername;

    /** Read-only sessions are rejected on every write verb by {@code ImpersonationFilter}. */
    @Column(name = "read_only", nullable = false)
    @Builder.Default
    private boolean readOnly = true;

    @Column(length = 255)
    private String reason;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 80)
    private String revokedBy;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private int requestCount = 0;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    public String getStatus() {
        if (revokedAt != null) {
            return "REVOKED";
        }
        return expiresAt.isAfter(LocalDateTime.now()) ? "ACTIVE" : "EXPIRED";
    }
}
