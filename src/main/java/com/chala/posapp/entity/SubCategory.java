package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "sub_categories",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "name"})
        },
        indexes = {
                @Index(name = "idx_tenant_subcategory_name", columnList = "tenant_id, name")
        }
)
@Getter
@Setter
public class SubCategory extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

}