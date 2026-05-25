package com.chala.posapp.repository;

import com.chala.posapp.entity.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, Long> {
    List<RecipeIngredient> findByParentItemId(Long parentItemId);
    Optional<RecipeIngredient> findByParentItemIdAndIngredientId(Long parentItemId, Long ingredientId);
    void deleteByParentItemId(Long parentItemId);
}
