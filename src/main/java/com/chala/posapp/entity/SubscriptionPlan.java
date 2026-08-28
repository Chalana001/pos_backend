package com.chala.posapp.entity;

import com.chala.posapp.entity.BillingCycle;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subscription_plans")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingCycle billingCycle;

    @Column(nullable = false)
    private double initialPrice;

    @Column(nullable = false)
    private double renewalPrice;

    @Column(nullable = false)
    private int maxBranches;

    /** Shown on the plan card in the super admin panel and on the POS pricing page. */
    @Column(length = 400)
    private String description;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /** An inactive plan cannot be assigned to a new shop; existing shops keep it. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * FREE, STANDARD and PRO are seeded by {@code SubscriptionPlanSeeder} and cannot be deleted.
     * Custom plans created from the panel are not system plans.
     */
    @Column(name = "system_plan", nullable = false)
    @Builder.Default
    private boolean systemPlan = false;

    /** Accent colour (hex) the panel uses for this plan's badge. */
    @Column(length = 20)
    private String color;

    /** Free days offered on this plan at onboarding. 0 means no trial. */
    @Column(name = "trial_days", nullable = false)
    @Builder.Default
    private int trialDays = 0;
}