package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Records that a shop closed a dismissible announcement, so it stops coming back. */
@Entity
@Table(name = "announcement_dismissals",
        uniqueConstraints = @UniqueConstraint(name = "uk_dismissal",
                columnNames = {"announcement_id", "tenant_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "dismissed_at", nullable = false, updatable = false)
    private LocalDateTime dismissedAt;

    @PrePersist
    void onCreate() {
        dismissedAt = LocalDateTime.now();
    }
}
