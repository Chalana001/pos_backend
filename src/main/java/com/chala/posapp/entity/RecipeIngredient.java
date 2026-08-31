package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "recipe_ingredients",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"parent_item_id", "ingredient_id"})
        },
        indexes = {
                @Index(name = "idx_recipe_parent", columnList = "parent_item_id"),
                @Index(name = "idx_recipe_ingredient", columnList = "ingredient_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipeIngredient extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_item_id", nullable = false)
    private Long parentItemId;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Column(nullable = false)
    private Integer quantity;
}
