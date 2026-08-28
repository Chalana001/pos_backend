package com.chala.posapp.entity;

import com.chala.posapp.module.ModuleCategory;
import jakarta.persistence.*;
import lombok.*;

/**
 * Database mirror of one {@link com.chala.posapp.module.ModuleDefinition}.
 *
 * <p>Rows are written by {@code ModuleCatalogSeeder} on boot and are never edited by hand —
 * the code catalog is authoritative. This table exists so the super admin panel can join
 * modules against {@code plan_modules} / {@code tenant_modules} in SQL, and so a module that
 * is removed from the catalog can be marked inactive rather than orphaning override rows.
 */
@Entity
@Table(name = "modules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module_key", nullable = false, unique = true, length = 60)
    private String moduleKey;

    @Column(name = "parent_key", length = 60)
    private String parentKey;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 400)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ModuleCategory category;

    @Column(length = 40)
    private String icon;

    /** Load-bearing module that can never be switched off. */
    @Column(nullable = false)
    private boolean locked;

    /** Fallback used when neither the plan nor the tenant has an explicit row. */
    @Column(name = "default_enabled", nullable = false)
    private boolean defaultEnabled;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** False once a module is dropped from the code catalog but override rows still exist. */
    @Column(nullable = false)
    private boolean active;
}
