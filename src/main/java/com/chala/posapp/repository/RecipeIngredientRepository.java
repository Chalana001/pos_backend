package com.chala.posapp.repository;

import com.chala.posapp.dto.item.RecipeIngredientLink;
import com.chala.posapp.entity.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, Long> {
    List<RecipeIngredient> findByParentItemId(Long parentItemId);
    Optional<RecipeIngredient> findByParentItemIdAndIngredientId(Long parentItemId, Long ingredientId);
    void deleteByParentItemId(Long parentItemId);

    // ------------------------------------------------------------------
    // Bulk relationship retrieval. The row population mirrors
    // findByParentItemId exactly: every recipe_ingredients row of the given
    // parents, one row per relationship, no filters. parent_item_id and
    // ingredient_id are plain columns (no item association), so no join is
    // needed and none is added; the stored quantity column is the response's
    // baseQuantity verbatim.
    //
    // NO ORDER BY on purpose: findByParentItemId declares none, ingredient
    // ordering is explicitly not part of the API contract, and introducing
    // one here would create a guarantee the legacy path never made. Callers
    // must match ingredients by ingredientItemId, never by list position,
    // and keep the repository's iteration order within each group.
    //
    // Empty itemIds is safe but not free: Hibernate renders the empty IN
    // list as a query matching no rows (no invalid IN () SQL), one
    // execution, empty result. The project convention for IN-list queries
    // is a service-level empty guard (PromotionGate.limitStates);
    // RecipeIngredientReader.loadForRecipes guards the same way — callers
    // must not rely on the repository for empty-input performance.
    // ------------------------------------------------------------------

    /**
     * The recipe → ingredient relationship rows of many recipe items at
     * once — the bulk form of {@link #findByParentItemId}, replacing one
     * per-recipe round trip with one query.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.item.RecipeIngredientLink(ri.parentItemId, ri.ingredientId, ri.quantity)
            FROM RecipeIngredient ri
            WHERE ri.parentItemId IN (:recipeItemIds)
            """)
    List<RecipeIngredientLink> findIngredientLinksForRecipes(@Param("recipeItemIds") Collection<Long> recipeItemIds);
}
