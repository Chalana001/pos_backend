package com.chala.posapp.service;

import com.chala.posapp.dto.item.IngredientItemData;
import com.chala.posapp.dto.item.ItemIngredientResponse;
import com.chala.posapp.dto.item.RecipeIngredientLink;
import com.chala.posapp.dto.item.RecipeIngredientValue;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.util.QuantityConversionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the bulk recipe ingredient reader and its immutable result: two bulk
 * repository calls per load (relationships, then distinct ingredient items —
 * never one call per recipe or per ingredient), the exact legacy response
 * semantics (1:1 value per relationship row, current ingredient metadata,
 * {@code baseQuantity} verbatim, display quantity through the existing
 * {@code QuantityConversionUtil}, {@code qtyUnit} = the ingredient's current
 * default unit, NO ordering guarantee), the legacy missing-ingredient
 * failure mode, and immutability of every map and list the result hands out.
 *
 * <p>Repositories are mocked so only the reader/result layer is under test;
 * the two queries themselves are exercised by the H2 and MySQL integration
 * tests. {@code QuantityConversionUtil} runs for real (static utility) so the
 * expected values are computed from source behavior, not invented.
 */
class RecipeIngredientReaderTest {

    private RecipeIngredientRepository recipeIngredientRepository;
    private ItemRepository itemRepository;
    private RecipeIngredientReader reader;

    @BeforeEach
    void setUp() {
        recipeIngredientRepository = mock(RecipeIngredientRepository.class);
        itemRepository = mock(ItemRepository.class);
        reader = new RecipeIngredientReader(recipeIngredientRepository, itemRepository);
    }

    // ---------------------------------------------------------------
    // Loading and query shape
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one bulk relationship call plus one bulk item call per load — never per recipe or per ingredient")
    void twoBulkCallsPerLoad() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L, 2L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 2000),
                new RecipeIngredientLink(1L, 11L, 100),
                new RecipeIngredientLink(2L, 10L, 2000)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L, 11L))).thenReturn(List.of(
                normalPcs(10L, "Egg", "EGG-1"),
                weightKg(11L, "Cheese", "CHS-1")));

        RecipeIngredientResult result = reader.loadForRecipes(List.of(1L, 2L));

        verify(recipeIngredientRepository).findIngredientLinksForRecipes(List.of(1L, 2L));
        verify(itemRepository).findIngredientDataByIdIn(List.of(10L, 11L));
        assertThat(result.ingredientsFor(1L)).hasSize(2);
        assertThat(result.ingredientsFor(2L)).hasSize(1);
    }

    @Test
    @DisplayName("an empty request returns an empty result and touches no repository")
    void emptyInputSkipsRepositories() {
        assertThat(reader.loadForRecipes(List.of()).isEmpty()).isTrue();
        assertThat(reader.loadForRecipes(null).isEmpty()).isTrue();
        verifyNoInteractions(recipeIngredientRepository, itemRepository);
    }

    @Test
    @DisplayName("no relationship rows means no second item lookup at all")
    void noRelationshipRowsSkipsItemLookup() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of());

        assertThat(reader.loadForRecipes(List.of(1L)).isEmpty()).isTrue();
        verifyNoInteractions(itemRepository);
    }

    // ---------------------------------------------------------------
    // Grouping — scenario A / B
    // ---------------------------------------------------------------

    @Test
    @DisplayName("rows group under their own recipe ids with overlapping and different ingredients")
    void rowsGroupByRecipeId() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 2000),
                new RecipeIngredientLink(2L, 10L, 4000),
                new RecipeIngredientLink(1L, 11L, 100),
                new RecipeIngredientLink(3L, 12L, 500)));
        when(itemRepository.findIngredientDataByIdIn(any())).thenReturn(List.of(
                normalPcs(10L, "Egg", "EGG-1"),
                weightKg(11L, "Cheese", "CHS-1"),
                weightKg(12L, "Flour", "FLR-1")));

        RecipeIngredientResult result = reader.loadForRecipes(List.of(1L, 2L, 3L));

        assertThat(result.valuesByRecipe()).containsOnlyKeys(1L, 2L, 3L);
        assertThat(ingredientIds(result.ingredientsFor(1L))).containsExactly(10L, 11L);
        assertThat(ingredientIds(result.ingredientsFor(2L))).containsExactly(10L);
        assertThat(ingredientIds(result.ingredientsFor(3L))).containsExactly(12L);
        // A requested recipe with no rows reads as an empty list, never null.
        assertThat(reader.loadForRecipes(List.of(1L)).ingredientsFor(999L)).isEmpty();
    }

    // ---------------------------------------------------------------
    // Current ingredient metadata — scenario C
    // ---------------------------------------------------------------

    @Test
    @DisplayName("name, barcode and unit come from the ingredient item's current row")
    void metadataComesFromCurrentItemRow() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 100)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                weightKg(10L, "Renamed Cheese", "NEW-BARCODE")));

        RecipeIngredientValue value = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L).get(0);

        assertThat(value.ingredientItemId()).isEqualTo(10L);
        assertThat(value.ingredientName()).isEqualTo("Renamed Cheese");
        assertThat(value.ingredientBarcode()).isEqualTo("NEW-BARCODE");
        assertThat(value.qtyUnit()).isEqualTo(MeasurementUnit.KG);
    }

    // ---------------------------------------------------------------
    // Quantity conversion — scenario D
    // ---------------------------------------------------------------

    @Test
    @DisplayName("NORMAL ingredient: baseQuantity verbatim, display quantity via QuantityConversionUtil, PCS unit")
    void normalIngredientConversion() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 2000)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                normalPcs(10L, "Egg", "EGG-1")));

        RecipeIngredientValue value = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L).get(0);

        // Normalized 2 PCS is stored as 2000 base units (characterized by
        // TASK-002); display = toDisplayQuantity(NORMAL, PCS, 2000) = 2.
        assertThat(value.baseQuantity()).isEqualTo(2000);
        assertThat(value.quantity()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(value.qtyUnit()).isEqualTo(MeasurementUnit.PCS);
        // Same value the legacy (Item, Integer) overload produces.
        assertThat(value.quantity()).isEqualByComparingTo(
                QuantityConversionUtil.toDisplayQuantity(ItemType.NORMAL, MeasurementUnit.PCS, 2000));
    }

    @Test
    @DisplayName("WEIGHT ingredient: baseQuantity verbatim, display in the item's default unit KG, requested G not echoed")
    void weightIngredientConversion() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 100)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                weightKg(10L, "Cheese", "CHS-1")));

        RecipeIngredientValue value = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L).get(0);

        // 100 G requested at creation is stored as 100 grams; the response
        // shows 0.1 in the ingredient's current default unit KG — the unit
        // requested at recipe creation time is never returned.
        assertThat(value.baseQuantity()).isEqualTo(100);
        assertThat(value.quantity()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(value.qtyUnit()).isEqualTo(MeasurementUnit.KG);
    }

    @Test
    @DisplayName("VOLUME ingredient converts through the same utility path")
    void volumeIngredientConversion() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 2000)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                new IngredientItemData(10L, "MILK-1", "Milk", ItemType.VOLUME, MeasurementUnit.L)));

        RecipeIngredientValue value = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L).get(0);

        // 2 L stored as 2000 milliliters, displayed as 2 in L.
        assertThat(value.baseQuantity()).isEqualTo(2000);
        assertThat(value.quantity()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(value.qtyUnit()).isEqualTo(MeasurementUnit.L);
    }

    // ---------------------------------------------------------------
    // Missing ingredient — scenario F
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a relationship pointing at an unloadable ingredient item throws the legacy ResourceNotFoundException")
    void missingIngredientThrowsLegacyException() {
        // The bulk item lookup is a JPQL query on Item, so soft-deleted rows
        // (@SQLRestriction) and plain absent rows both come back missing —
        // exactly like the legacy findAllById map lookup.
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 42L, 2000)));
        when(itemRepository.findIngredientDataByIdIn(List.of(42L))).thenReturn(List.of());

        assertThatThrownBy(() -> reader.loadForRecipes(List.of(1L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Ingredient item not found: 42");
    }

    // ---------------------------------------------------------------
    // Ordering — scenario G
    // ---------------------------------------------------------------

    @Test
    @DisplayName("no ordering guarantee is created: values keep repository iteration order and match by ingredientItemId")
    void noOrderingGuarantee() {
        // Rows deliberately arrive "out of order"; the reader neither sorts
        // nor promises order — callers compare by ingredientItemId.
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 1),
                new RecipeIngredientLink(1L, 12L, 2),
                new RecipeIngredientLink(1L, 11L, 3)));
        when(itemRepository.findIngredientDataByIdIn(any())).thenReturn(List.of(
                normalPcs(10L, "A", "A-1"),
                normalPcs(12L, "C", "C-1"),
                normalPcs(11L, "B", "B-1")));

        List<RecipeIngredientValue> values = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L);

        // Iteration order is preserved (10, 12, 11), NOT sorted — and that
        // is not a defect to "fix" by sorting.
        assertThat(ingredientIds(values)).containsExactly(10L, 12L, 11L);
    }

    // ---------------------------------------------------------------
    // Duplicate / uniqueness behavior — scenario H
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one value per relationship row with no de-duplication — the unique constraint owns uniqueness")
    void oneValuePerRowWithoutDeduplication() {
        // The (parent_item_id, ingredient_id) unique constraint makes true
        // duplicate rows impossible (asserted by the integration tests); if
        // duplicated rows ever reach the reader, the legacy mapper is 1:1
        // over rows and so is this reader — no silent collapse here either.
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 1),
                new RecipeIngredientLink(1L, 10L, 1)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                normalPcs(10L, "Egg", "EGG-1")));

        List<RecipeIngredientValue> values = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L);

        assertThat(values).hasSize(2);
    }

    // ---------------------------------------------------------------
    // Immutability
    // ---------------------------------------------------------------

    @Test
    @DisplayName("no caller can mutate the result map or the value lists")
    void resultIsImmutable() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 2000)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                normalPcs(10L, "Egg", "EGG-1")));

        RecipeIngredientResult result = reader.loadForRecipes(List.of(1L));
        Map<Long, List<RecipeIngredientValue>> exposed = result.valuesByRecipe();

        assertThatThrownBy(() -> exposed.put(2L, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> exposed.remove(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.ingredientsFor(1L).add(
                new RecipeIngredientValue(1L, "X", "X", BigDecimal.ONE, 1, MeasurementUnit.PCS)))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(result.ingredientsFor(1L)).hasSize(1);
        assertThat(exposed).containsOnlyKeys(1L);
    }

    @Test
    @DisplayName("the empty result is immutable as well")
    void emptyResultIsImmutable() {
        Map<Long, List<RecipeIngredientValue>> exposed = RecipeIngredientResult.empty().valuesByRecipe();
        assertThatThrownBy(() -> exposed.put(1L, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------
    // Response DTO contract
    // ---------------------------------------------------------------

    @Test
    @DisplayName("toResponse builds the existing ItemIngredientResponse field for field")
    void toResponsePreservesTheDtoContract() {
        when(recipeIngredientRepository.findIngredientLinksForRecipes(List.of(1L))).thenReturn(List.of(
                new RecipeIngredientLink(1L, 10L, 100)));
        when(itemRepository.findIngredientDataByIdIn(List.of(10L))).thenReturn(List.of(
                weightKg(10L, "Cheese", "CHS-1")));

        ItemIngredientResponse response = reader.loadForRecipes(List.of(1L)).ingredientsFor(1L).get(0).toResponse();

        assertThat(response.getIngredientItemId()).isEqualTo(10L);
        assertThat(response.getIngredientBarcode()).isEqualTo("CHS-1");
        assertThat(response.getIngredientName()).isEqualTo("Cheese");
        assertThat(response.getBaseQuantity()).isEqualTo(100);
        assertThat(response.getQuantity()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(response.getQtyUnit()).isEqualTo(MeasurementUnit.KG);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private List<Long> ingredientIds(List<RecipeIngredientValue> values) {
        List<Long> ids = new ArrayList<>();
        for (RecipeIngredientValue value : values) {
            ids.add(value.ingredientItemId());
        }
        return ids;
    }

    private IngredientItemData normalPcs(long id, String name, String barcode) {
        return new IngredientItemData(id, barcode, name, ItemType.NORMAL, MeasurementUnit.PCS);
    }

    private IngredientItemData weightKg(long id, String name, String barcode) {
        return new IngredientItemData(id, barcode, name, ItemType.WEIGHT, MeasurementUnit.KG);
    }
}
