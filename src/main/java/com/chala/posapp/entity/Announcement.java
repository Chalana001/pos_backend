package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A message shown inside the POS app of the shops it targets.
 *
 * <p>Targeting is a pair — {@link #audience} says what kind of filter, {@link #audienceValue}
 * says which one — rather than four nullable columns, because exactly one filter applies at a
 * time and nullable columns would let two be set at once.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    public enum Severity { INFO, WARNING, CRITICAL }

    /** ALL | PLAN | TENANT | MODULE — MODULE targets shops that have a given module on. */
    public enum Audience { ALL, PLAN, TENANT, MODULE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Severity severity = Severity.INFO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Audience audience = Audience.ALL;

    /** Plan id, tenant id or module key depending on {@link #audience}. Null for ALL. */
    @Column(name = "audience_value", length = 255)
    private String audienceValue;

    @Column(name = "active_from")
    private LocalDateTime activeFrom;

    @Column(name = "active_until")
    private LocalDateTime activeUntil;

    /** A CRITICAL notice usually should not be dismissible; that is the operator's call. */
    @Column(nullable = false)
    @Builder.Default
    private boolean dismissible = true;

    /** Drafts are invisible to shops until this is set. */
    @Column(nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "link_label", length = 80)
    private String linkLabel;

    @Column(name = "created_by", length = 80)
    private String createdBy;

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

    /** Published and inside its date window. Says nothing about who it targets. */
    public boolean isLive() {
        if (!published) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (activeFrom != null && activeFrom.isAfter(now)) {
            return false;
        }
        return activeUntil == null || activeUntil.isAfter(now);
    }

    public String getStatus() {
        if (!published) {
            return "DRAFT";
        }
        LocalDateTime now = LocalDateTime.now();
        if (activeFrom != null && activeFrom.isAfter(now)) {
            return "SCHEDULED";
        }
        if (activeUntil != null && activeUntil.isBefore(now)) {
            return "ENDED";
        }
        return "LIVE";
    }
}
