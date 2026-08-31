package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "sub_categories",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"name"})
        },
        indexes = {
                @Index(name = "idx_subcategory_name", columnList = "name")
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