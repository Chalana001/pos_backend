package com.chala.posapp.repository;

import com.chala.posapp.entity.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, Long> {
    List<RecipeIngredient> findByParentItemId(Long parentItemId);
    void deleteByParentItemId(Long parentItemId);
}
