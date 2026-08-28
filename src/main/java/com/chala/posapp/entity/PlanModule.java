package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A plan's default answer for one module. Acts as the template applied when a shop is
 * onboarded or moved onto the plan; a shop can then deviate via {@link TenantModule}.
 */
@Entity
@Table(name = "plan_modules",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_modules", columnNames = {"plan_id", "module_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "module_key", nullable = false, length = 60)
    private String moduleKey;

    @Column(nullable = false)
    private boolean enabled;
}
