package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    @Column(name = "shop_name", nullable = false, length = 120)
    private String shopName;

    @Column(name = "admin_username", nullable = false, length = 80)
    private String adminUsername;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private boolean blocked;

    @Column(nullable = false)
    private int extraBranches;

    @Column(nullable = false)
    private LocalDateTime validUntil;

    @Column(length = 255)
    private String notes;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "contact_email", length = 120)
    private String contactEmail;

    /** Drives which module preset the panel offers when onboarding or resetting to plan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 20)
    @Builder.Default
    private ShopBusinessType businessType = ShopBusinessType.RETAIL;

    /** A trial ends by itself at {@link #trialEndsAt} unless it is converted to a paid plan. */
    @Column(name = "is_trial", nullable = false)
    @Builder.Default
    private boolean trial = false;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    /**
     * Days after {@link #validUntil} during which the shop still works. This is why a lapse
     * on a Friday evening does not stop trading before anyone can be reached.
     */
    @Column(name = "grace_days", nullable = false)
    @Builder.Default
    private int graceDays = 0;

    @Column(name = "last_reminder_type", length = 20)
    private String lastReminderType;

    @Column(name = "last_reminder_at")
    private LocalDateTime lastReminderAt;

    /**
     * Distinct from {@link #blocked}: blocked means "you have not paid", maintenance means
     * "we are working on your data right now". Different message, different HTTP status,
     * and a shop in maintenance is not a billing problem.
     */
    @Column(name = "maintenance_mode", nullable = false)
    @Builder.Default
    private boolean maintenanceMode = false;

    @Column(name = "maintenance_message", length = 500)
    private String maintenanceMessage;

    /** The moment access actually stops — the paid-until date plus any grace. */
    public LocalDateTime getAccessEndsAt() {
        return graceDays > 0 ? validUntil.plusDays(graceDays) : validUntil;
    }

    public boolean isWithinGrace() {
        LocalDateTime now = LocalDateTime.now();
        return validUntil.isBefore(now) && getAccessEndsAt().isAfter(now);
    }

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
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
