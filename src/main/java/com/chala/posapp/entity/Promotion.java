package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "promotions",
        indexes = {
                @Index(name = "idx_tenant_promotion_active_dates", columnList = "tenant_id, active, start_at, end_at"),
                @Index(name = "idx_tenant_promotion_branch", columnList = "tenant_id, branch_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromotionScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private double discountValue;

    @Column(name = "min_bill_amount", nullable = false)
    @Builder.Default
    private double minBillAmount = 0.0;

    @Column(name = "max_discount_amount", nullable = false)
    @Builder.Default
    private double maxDiscountAmount = 0.0;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private int priority = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PromotionTarget> targets = new ArrayList<>();

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
