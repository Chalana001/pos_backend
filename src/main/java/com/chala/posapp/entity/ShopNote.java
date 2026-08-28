package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A support note against a shop — what the owner asked for, what was promised, why an
 * exception was granted.
 *
 * <p>Deliberately separate from {@link SuperAdminAuditLog}: the audit trail is what the
 * system did and is append-only, while these are what the humans said and can be edited,
 * pinned and deleted.
 */
@Entity
@Table(name = "shop_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** GENERAL, BILLING, TECHNICAL, COMPLAINT or FOLLOW_UP — free text, filtered in the panel. */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String category = "GENERAL";

    /** Pinned notes sort to the top and are the ones an operator sees first. */
    @Column(nullable = false)
    @Builder.Default
    private boolean pinned = false;

    @Column(nullable = false, length = 80)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
