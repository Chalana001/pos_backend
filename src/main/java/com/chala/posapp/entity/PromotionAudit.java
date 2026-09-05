package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Who did what to a promotion, and when. Written on every edit and state change. */
@Entity
@Table(
        name = "promotion_audit",
        indexes = @Index(name = "idx_promotion_audit_promotion", columnList = "promotion_id, at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionAudit extends TenantEntity {

    public enum Action {
        CREATED, UPDATED, SUBMITTED, APPROVED, REJECTED, ACTIVATED, PAUSED, RESUMED, ARCHIVED, DUPLICATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Action action;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 120)
    private String username;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime at;
}
