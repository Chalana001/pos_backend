package com.chala.posapp.service;

import com.chala.posapp.dto.item.IngredientItemData;
import com.chala.posapp.dto.item.RecipeIngredientLink;
import com.chala.posapp.dto.item.RecipeIngredientValue;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk reader for recipe ingredients: one relationship query plus at most one
 * bulk ingredient-item query replace the per-recipe
 * {@code findByParentItemId} + {@code findAllById} pair the POS item paths
 * make today, without changing what those calls answer.
 *
 * <p>Stateless singleton — it holds only its repository dependencies and
 * never retains tenant, recipe or ingredient values between calls. Each
 * invocation builds a fresh {@link RecipeIngredientResult} for the current
 * request and tenant; nothing is cached globally, nothing lives in a
 * ThreadLocal, and no JPA entity leaves the repositories. The ingredient
 * items are loaded as the scalar {@link IngredientItemData} projection and
 * converted during this call only.
 *
 * <p>Quantity conversion reuses {@code QuantityConversionUtil} unchanged:
 * {@code ItemService.mapIngredientResponse} calls
 * {@code toDisplayQuantity(item, baseQuantity)}, and that overload is a
 * one-line delegation to {@code toDisplayQuantity(itemType, defaultUnit,
 * baseQuantity)} — the method called here with the projected fields. Same
 * class, same code path, no second algorithm.
 *
 * <p>Missing referenced items keep the legacy failure mode:
 * {@link ResourceNotFoundException} with the legacy message, thrown while
 * building the snapshot — the ingredient is never silently omitted and no
 * incomplete recipe is returned. A soft-deleted ingredient item counts as
 * missing exactly as it does for the legacy {@code findAllById} lookup,
 * because the bulk item query is a JPQL query on {@code Item} and therefore
 * carries the same {@code @SQLRestriction("deleted_at IS NULL")}.
 */
@Service
@RequiredArgsConstructor
public class RecipeIngredientReader {

    private final RecipeIngredientRepository recipeIngredientRepository;

    private final ItemRepository itemRepository;

    /**
     * Loads the ingredient lines of the given recipe items in at most two
     * queries.
     *
     * <p>An empty (or null) request returns {@link RecipeIngredientResult#empty()}
     * without touching the database — the service-level empty guard the
     * project applies to IN-list queries (see {@code PromotionGate.limitStates};
     * the repository stays safe if called with an empty collection but that
     * costs one harmless query, so callers must not rely on it). When the
     * relationship query returns no rows at all, no second item lookup is
     * issued.
     *
     * @param recipeItemIds the parent recipe item ids to load ingredients
     *                      for; duplicates are harmless. Not interpreted
     *                      further — the legacy eligibility and mapping
     *                      paths decide which items are RECIPE items before
     *                      they run, and so must the future assembler.
     * @return an immutable, request-local snapshot grouped by recipe item id
     */
    public RecipeIngredientResult loadForRecipes(Collection<Long> recipeItemIds) {
        if (recipeItemIds == null || recipeItemIds.isEmpty()) {
            return RecipeIngredientResult.empty();
        }

        List<RecipeIngredientLink> links =
                recipeIngredientRepository.findIngredientLinksForRecipes(recipeItemIds);
        if (links.isEmpty()) {
            return RecipeIngredientResult.empty();
        }

        List<Long> ingredientIds = links.stream()
                .map(RecipeIngredientLink::ingredientId)
                .distinct()
                .toList();
        Map<Long, IngredientItemData> itemsById = itemRepository.findIngredientDataByIdIn(ingredientIds).stream()
                .collect(Collectors.toMap(IngredientItemData::itemId, Function.identity()));

        // One value per relationship row, in repository iteration order —
        // the legacy mapper is 1:1 with no ordering guarantee and no
        // de-duplication (the (parent_item_id, ingredient_id) unique
        // constraint keeps rows unique at the database level).
        Map<Long, List<RecipeIngredientValue>> grouped = new LinkedHashMap<>();
        for (RecipeIngredientLink link : links) {
            IngredientItemData item = itemsById.get(link.ingredientId());
            if (item == null) {
                // Same failure the legacy mapper produces for a missing or
                // soft-deleted ingredient item.
                throw new ResourceNotFoundException("Ingredient item not found: " + link.ingredientId());
            }
            grouped.computeIfAbsent(link.parentItemId(), key -> new ArrayList<>())
                    .add(valueFor(link, item));
        }
        return RecipeIngredientResult.from(grouped);
    }

    /**
     * Mirrors {@code ItemService.mapIngredientResponse} field for field:
     * identity and {@code qtyUnit} from the ingredient item's current row,
     * {@code baseQuantity} verbatim from the stored relationship quantity,
     * display {@code quantity} through the existing conversion utility.
     */
    private RecipeIngredientValue valueFor(RecipeIngredientLink link, IngredientItemData item) {
        return new RecipeIngredientValue(
                item.itemId(),
                item.barcode(),
                item.name(),
                QuantityConversionUtil.toDisplayQuantity(item.itemType(), item.defaultUnit(), link.baseQuantity()),
                link.baseQuantity(),
                item.defaultUnit());
    }
}
