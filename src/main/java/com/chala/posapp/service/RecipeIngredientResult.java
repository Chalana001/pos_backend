package com.chala.posapp.service;

import com.chala.posapp.dto.item.RecipeIngredientValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, request-local snapshot of the recipe ingredient lines produced
 * by {@link RecipeIngredientReader}, grouped by parent recipe item id.
 *
 * <p>It reproduces exactly what {@code ItemService.mapRecipeIngredientResponses}
 * answers today, per recipe:
 * <ul>
 *   <li>one value per {@code recipe_ingredients} row — the legacy mapper is
 *       1:1 over rows with no de-duplication (the (parent_item_id,
 *       ingredient_id) unique constraint keeps rows unique at the database
 *       level);</li>
 *   <li>ingredient metadata and {@code qtyUnit} read from the ingredient
 *       item's <em>current</em> row, {@code baseQuantity} from the stored
 *       normalized relationship quantity, display {@code quantity} from
 *       {@code QuantityConversionUtil};</li>
 *   <li><b>no ordering guarantee</b> — {@code findByParentItemId} declares no
 *       ORDER BY and neither does the bulk query. Values are kept in the
 *       repository's iteration order within each recipe, and callers must
 *       match by {@code ingredientItemId}, never by list position;</li>
 *   <li>a recipe with no ingredient rows yields an empty list, never
 *       {@code null}.</li>
 * </ul>
 *
 * <p>The snapshot holds only immutable {@link RecipeIngredientValue} records —
 * never JPA entities — and every map and list it hands out is unmodifiable.
 * Nothing is cached or shared between invocations; each reader call builds a
 * fresh snapshot for the current request and tenant.
 */
public final class RecipeIngredientResult {

    private static final RecipeIngredientResult EMPTY =
            new RecipeIngredientResult(Collections.emptyMap());

    /** Recipe item id → ingredient values, in repository iteration order. */
    private final Map<Long, List<RecipeIngredientValue>> valuesByRecipe;

    private RecipeIngredientResult(Map<Long, List<RecipeIngredientValue>> valuesByRecipe) {
        this.valuesByRecipe = valuesByRecipe;
    }

    /** An empty snapshot — the answer for an empty request. */
    public static RecipeIngredientResult empty() {
        return EMPTY;
    }

    /**
     * Groups pre-built values per recipe, preserving the given list order —
     * one value per relationship row, no de-duplication, mirroring the
     * legacy 1:1 mapping. Each list is defensively copied into an immutable
     * copy so no caller can mutate the snapshot.
     */
    public static RecipeIngredientResult from(Map<Long, List<RecipeIngredientValue>> grouped) {
        Map<Long, List<RecipeIngredientValue>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Long, List<RecipeIngredientValue>> entry : grouped.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return new RecipeIngredientResult(Collections.unmodifiableMap(snapshot));
    }

    /**
     * The ingredient lines of one recipe in repository iteration order.
     * A recipe with no ingredient rows (or never loaded) returns an empty
     * immutable list — never {@code null}.
     */
    public List<RecipeIngredientValue> ingredientsFor(Long recipeItemId) {
        return valuesByRecipe.getOrDefault(recipeItemId, List.of());
    }

    /** The full recipe-id → ingredient-lines snapshot. Map and lists are unmodifiable. */
    public Map<Long, List<RecipeIngredientValue>> valuesByRecipe() {
        return valuesByRecipe;
    }

    public boolean isEmpty() {
        return valuesByRecipe.isEmpty();
    }
}
