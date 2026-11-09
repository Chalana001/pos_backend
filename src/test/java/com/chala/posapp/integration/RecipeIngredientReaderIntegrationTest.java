package com.chala.posapp.integration;

import com.chala.posapp.dto.item.RecipeIngredientValue;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.RecipeIngredient;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.ItemRepository;
import com.chala.posapp.repository.RecipeIngredientRepository;
import com.chala.posapp.service.RecipeIngredientReader;
import com.chala.posapp.service.RecipeIngredientResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-level verification of the bulk recipe ingredient reader
 * ({@code RecipeIngredientReader.loadForRecipes} over
 * {@code RecipeIngredientRepository.findIngredientLinksForRecipes} +
 * {@code ItemRepository.findIngredientDataByIdIn}) against the legacy
 * per-recipe reads on identical fixtures: {@code findByParentItemId} plus
 * {@code ItemService.mapRecipeIngredientResponses} current mapping behavior.
 *
 * <p>Rules pinned here: one value per relationship row (1:1, no
 * de-duplication — the (parent_item_id, ingredient_id) unique constraint owns
 * uniqueness); ingredient metadata and {@code qtyUnit} from the ingredient
 * item's CURRENT row; {@code baseQuantity} verbatim from the stored
 * normalized quantity; display {@code quantity} via the existing
 * {@code QuantityConversionUtil}; and NO ordering guarantee — ingredients
 * are matched by {@code ingredientItemId}, never by list position.
 *
 * <p>Physical tenant isolation is not representable on the shared H2 schema;
 * that verification lives in {@code RecipeIngredientMysqlIntegrationTest} on
 * real MySQL with two provisioned tenant databases.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RecipeIngredientReaderIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private RecipeIngredientRepository recipeIngredientRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private RecipeIngredientReader reader;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ---------------------------------------------------------------
    // A. Multiple recipe parents
    // ---------------------------------------------------------------

    @Test
    @DisplayName("multiple recipe parents with overlapping and different ingredients group under their own ids")
    void multipleRecipeParentsGroupCorrectly() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item first = newRecipe("Omelette");
        Item second = newRecipe("Pancake");
        Item third = newRecipe("Plain");
        link(first, egg, 2000);
        link(first, cheese, 100);
        link(second, egg, 4000);

        RecipeIngredientResult result = reader.loadForRecipes(
                List.of(first.getId(), second.getId(), third.getId()));

        assertThat(result.valuesByRecipe()).containsOnlyKeys(first.getId(), second.getId());
        assertThat(ingredientIds(result.ingredientsFor(first.getId())))
                .containsExactlyInAnyOrder(egg.getId(), cheese.getId());
        assertThat(ingredientIds(result.ingredientsFor(second.getId()))).containsExactly(egg.getId());
        // A recipe with no rows reads as an empty list, never null.
        assertThat(result.ingredientsFor(third.getId())).isEmpty();
        assertThat(result.ingredientsFor(third.getId())).isNotNull();
    }

    // ---------------------------------------------------------------
    // B. Multiple ingredients
    // ---------------------------------------------------------------

    @Test
    @DisplayName("every relationship of a recipe is returned for the correct parent")
    void allIngredientsReturnedForCorrectParent() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item milk = newIngredient("Milk", ItemType.VOLUME, MeasurementUnit.L);
        Item recipe = newRecipe("Three-way");
        link(recipe, egg, 2000);
        link(recipe, cheese, 100);
        link(recipe, milk, 2000);

        List<RecipeIngredientValue> values = reader.loadForRecipes(List.of(recipe.getId()))
                .ingredientsFor(recipe.getId());

        assertThat(values).hasSize(3);
        assertThat(ingredientIds(values))
                .containsExactlyInAnyOrder(egg.getId(), cheese.getId(), milk.getId());
    }

    // ---------------------------------------------------------------
    // C. Current ingredient metadata
    // ---------------------------------------------------------------

    @Test
    @DisplayName("name, barcode and default unit are read from the ingredient item's current row")
    void metadataComesFromCurrentItemRow() {
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item recipe = newRecipe("Renamed");
        link(recipe, cheese, 100);

        // The item changes after the recipe was written; the response must
        // reflect the CURRENT row, exactly like the legacy per-item lookup.
        cheese.setName("Renamed Cheese");
        cheese.setBarcode("NEW-BARCODE-1");
        cheese.setDefaultUnit(MeasurementUnit.G);
        itemRepository.save(cheese);

        RecipeIngredientValue value = reader.loadForRecipes(List.of(recipe.getId()))
                .ingredientsFor(recipe.getId()).get(0);

        assertThat(value.ingredientItemId()).isEqualTo(cheese.getId());
        assertThat(value.ingredientName()).isEqualTo("Renamed Cheese");
        assertThat(value.ingredientBarcode()).isEqualTo("NEW-BARCODE-1");
        assertThat(value.qtyUnit()).isEqualTo(MeasurementUnit.G);
    }

    // ---------------------------------------------------------------
    // D. Quantity conversion
    // ---------------------------------------------------------------

    @Test
    @DisplayName("NORMAL and WEIGHT ingredients keep baseQuantity verbatim and convert display quantity through the existing utility")
    void quantityConversionMatchesCharacterizedBehavior() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item recipe = newRecipe("Conversion");
        // Stored normalized quantities exactly as the write path persists
        // them (TASK-002 characterization): 2 PCS → 2000, 100 G → 100.
        link(recipe, egg, 2000);
        link(recipe, cheese, 100);

        List<RecipeIngredientValue> values = reader.loadForRecipes(List.of(recipe.getId()))
                .ingredientsFor(recipe.getId());
        RecipeIngredientValue eggLine = find(values, egg.getId());
        RecipeIngredientValue cheeseLine = find(values, cheese.getId());

        // NORMAL: baseQuantity 2000, display 2, unit PCS.
        assertThat(eggLine.baseQuantity()).isEqualTo(2000);
        assertThat(eggLine.quantity()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(eggLine.qtyUnit()).isEqualTo(MeasurementUnit.PCS);
        // WEIGHT: baseQuantity 100 grams, display 0.1 in the ingredient's
        // default unit KG — the unit requested at creation time (G) is never
        // echoed back.
        assertThat(cheeseLine.baseQuantity()).isEqualTo(100);
        assertThat(cheeseLine.quantity()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(cheeseLine.qtyUnit()).isEqualTo(MeasurementUnit.KG);
    }

    // ---------------------------------------------------------------
    // E. Empty recipe
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a recipe with no ingredient rows receives an empty list")
    void emptyRecipeGetsEmptyList() {
        Item recipe = newRecipe("Empty");

        RecipeIngredientResult result = reader.loadForRecipes(List.of(recipe.getId()));

        assertThat(result.ingredientsFor(recipe.getId())).isEmpty();
        assertThat(result.ingredientsFor(recipe.getId())).isNotNull();
    }

    // ---------------------------------------------------------------
    // F. Missing ingredient (isolated edge-case tests)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a relationship pointing at a nonexistent item fails with the legacy exception on both paths")
    void danglingRelationshipFailsLikeLegacy() throws Exception {
        // The recipe_ingredients table has no foreign key on ingredient_id
        // (verified in V1__baseline.sql), so a dangling row is representable
        // at the database level. The public write path validates ingredient
        // existence and can never create one, so this row is seeded directly
        // as an isolated fixture — the reader must not silently omit it.
        TenantFixture fixture = seedTenantShop(uniqueKey("dang"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        long subCategoryId = createSubCategory();
        JsonNode recipeItem = postJson("/items", fixture.tenantId(), token, """
                {
                  "barcode": "%s",
                  "name": "Dangling recipe",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 500,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "ingredients": []
                }
                """.formatted(uniqueKey("DANG"), subCategoryId));
        long recipeId = recipeItem.path("id").asLong();
        long missingId = 987_654_321L;
        recipeIngredientRepository.save(RecipeIngredient.builder()
                .parentItemId(recipeId)
                .ingredientId(missingId)
                .quantity(2000)
                .build());

        // The new reader fails exactly like the legacy mapper.
        assertThatThrownBy(() -> reader.loadForRecipes(List.of(recipeId)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Ingredient item not found: " + missingId);
        // …and the legacy response mapping fails the same way for the same
        // fixture (the public GET /items/{id} endpoint surfaces 404).
        jsonRequest(org.springframework.http.HttpMethod.GET, "/items/" + recipeId,
                fixture.tenantId(), token, null, 404);
    }

    @Test
    @DisplayName("a soft-deleted ingredient item reads as missing, exactly like the legacy findAllById lookup")
    void softDeletedIngredientReadsAsMissing() {
        // @SQLRestriction("deleted_at IS NULL") hides the item from every
        // JPA query — the legacy findAllById and the bulk projection alike.
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item recipe = newRecipe("Soft deleted");
        link(recipe, egg, 2000);
        egg.setActive(false);
        egg.setDeletedAt(java.time.LocalDateTime.now());
        itemRepository.save(egg);

        assertThatThrownBy(() -> reader.loadForRecipes(List.of(recipe.getId())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Ingredient item not found: " + egg.getId());
    }

    // ---------------------------------------------------------------
    // G. Unordered ingredient results
    // ---------------------------------------------------------------

    @Test
    @DisplayName("ingredients compare by membership — no ordering guarantee is asserted or created")
    void ingredientsAreUnordered() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item cheese = newIngredient("Cheese", ItemType.WEIGHT, MeasurementUnit.KG);
        Item milk = newIngredient("Milk", ItemType.VOLUME, MeasurementUnit.L);
        Item recipe = newRecipe("Unordered");
        link(recipe, egg, 2000);
        link(recipe, cheese, 100);
        link(recipe, milk, 2000);

        List<RecipeIngredientValue> values = reader.loadForRecipes(List.of(recipe.getId()))
                .ingredientsFor(recipe.getId());

        // Membership only (containsExactlyInAnyOrder), matched by
        // ingredientItemId — the repository query has no ORDER BY and the
        // response contract promises none.
        assertThat(ingredientIds(values))
                .containsExactlyInAnyOrder(egg.getId(), cheese.getId(), milk.getId());
    }

    // ---------------------------------------------------------------
    // H. Duplicate and uniqueness behavior
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the (parent_item_id, ingredient_id) unique constraint rejects duplicate rows")
    void uniqueConstraintRejectsDuplicateRows() {
        // recipe_ingredients carries UNIQUE (parent_item_id, ingredient_id) —
        // entity @UniqueConstraint and V1's UKqw8ho7v09p3ej3kqa39g9reyp are
        // the same constraint. A second row for the same pair is rejected by
        // the database; nothing invalid is inserted and no constraint is
        // bypassed to prove it. The reader itself maps rows 1:1 (unit test).
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item recipe = newRecipe("Duplicated");
        link(recipe, egg, 2000);

        assertThatThrownBy(() -> recipeIngredientRepository.saveAndFlush(RecipeIngredient.builder()
                .parentItemId(recipe.getId())
                .ingredientId(egg.getId())
                .quantity(4000)
                .build()))
                .isInstanceOf(DataAccessException.class);

        // The surviving row still maps to exactly one value.
        assertThat(reader.loadForRecipes(List.of(recipe.getId())).ingredientsFor(recipe.getId()))
                .hasSize(1);
    }

    // ---------------------------------------------------------------
    // I. Query count
    // ---------------------------------------------------------------

    @Test
    @DisplayName("many recipes cost two bulk queries, and result access runs zero SQL")
    void queryCountsAreBounded() {
        List<Long> recipeIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Item ingredient = newIngredient("Ingredient " + i, ItemType.NORMAL, MeasurementUnit.PCS);
            Item recipe = newRecipe("Recipe " + i);
            link(recipe, ingredient, 2000);
            recipeIds.add(recipe.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        RecipeIngredientResult result = reader.loadForRecipes(recipeIds);
        assertThat(statistics.getQueryExecutionCount())
                .as("5 recipes must cost two queries (relationships + items), not 5 or 10")
                .isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

        // Repeated lookups in the immutable result touch no SQL.
        statistics.clear();
        for (int round = 0; round < 3; round++) {
            for (Long recipeId : recipeIds) {
                for (RecipeIngredientValue value : result.ingredientsFor(recipeId)) {
                    value.ingredientName();
                    value.toResponse();
                }
                result.valuesByRecipe();
            }
        }
        assertThat(statistics.getQueryExecutionCount()).isZero();

        // Empty request: zero queries (reader-level guard).
        statistics.clear();
        assertThat(reader.loadForRecipes(List.of()).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    @DisplayName("when no relationship rows exist, the second item lookup is skipped entirely")
    void noRowsMeansOneQueryOnly() {
        Item emptyRecipe = newRecipe("No rows");
        Statistics statistics = statistics();

        statistics.clear();
        assertThat(reader.loadForRecipes(List.of(emptyRecipe.getId())).isEmpty()).isTrue();
        assertThat(statistics.getQueryExecutionCount())
                .as("relationship query only — no ingredient item lookup without rows")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // J. Large ID collection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("6,001 recipe parent ids bind safely and group correctly")
    void largeRecipeIdCollection() {
        Item egg = newIngredient("Egg", ItemType.NORMAL, MeasurementUnit.PCS);
        Item recipe = newRecipe("Real recipe");
        link(recipe, egg, 2000);

        // 6,000 non-matching filler ids plus the one real recipe, mirroring
        // the catalogue scale these queries are built for. Binding and
        // grouping only — no performance claim is made here.
        List<Long> ids = new ArrayList<>();
        for (long id = 20_000_000L; id < 20_006_000L; id++) {
            ids.add(id);
        }
        ids.add(recipe.getId());

        Statistics statistics = statistics();
        statistics.clear();
        RecipeIngredientResult result = reader.loadForRecipes(ids);

        assertThat(result.valuesByRecipe()).containsOnlyKeys(recipe.getId());
        assertThat(ingredientIds(result.ingredientsFor(recipe.getId()))).containsExactly(egg.getId());
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be two queries")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------
    // K. Legacy equivalence (end-to-end through the real API mapping)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("reader data equals the live ItemResponse ingredient mapping field for field")
    void readerMatchesLiveLegacyMapping() throws Exception {
        // Fixtures go through the PUBLIC write path so the stored normalized
        // quantities are produced by the real recipe workflow; the legacy
        // side of the comparison is the live GET /items/{id} mapping
        // (ItemService.mapToResponse → mapRecipeIngredientResponses).
        TenantFixture fixture = seedTenantShop(uniqueKey("rec"), 1);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);
        long subCategoryId = createSubCategory();

        JsonNode eggItem = postJson("/items", fixture.tenantId(), token, """
                {
                  "barcode": "%s",
                  "name": "Qk farm egg",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0
                }
                """.formatted(uniqueKey("RECI"), subCategoryId));
        JsonNode cheeseItem = postJson("/items", fixture.tenantId(), token, """
                {
                  "barcode": "%s",
                  "name": "Qk cheese",
                  "subCategoryId": %d,
                  "costPrice": 200,
                  "sellingPrice": 300,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(uniqueKey("RECI"), subCategoryId));
        long eggId = eggItem.path("id").asLong();
        long cheeseId = cheeseItem.path("id").asLong();

        JsonNode recipeItem = postJson("/items", fixture.tenantId(), token, """
                {
                  "barcode": "%s",
                  "name": "Qk omelette",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 500,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "ingredients": [
                    {"ingredientItemId": %d, "quantity": 2},
                    {"ingredientItemId": %d, "quantity": 100, "qtyUnit": "G"}
                  ]
                }
                """.formatted(uniqueKey("RECI"), subCategoryId, eggId, cheeseId));
        long recipeId = recipeItem.path("id").asLong();

        // Legacy side: the live response mapping.
        JsonNode fetched = getJson("/items/" + recipeId, fixture.tenantId(), token);
        JsonNode legacyIngredients = fetched.path("ingredients");
        assertThat(legacyIngredients.size()).isEqualTo(2);

        // New side: the bulk reader.
        RecipeIngredientResult result = reader.loadForRecipes(List.of(recipeId));
        List<RecipeIngredientValue> values = result.ingredientsFor(recipeId);
        assertThat(values).hasSize(2);

        // Parent id: the reader's grouping key is the response's item id.
        assertThat(fetched.path("id").asLong()).isEqualTo(recipeId);

        // Field-by-field, matched by ingredientItemId (TASK-004's ingredient
        // comparison convention: unordered set, never list position).
        for (JsonNode legacyLine : legacyIngredients) {
            long ingredientItemId = legacyLine.path("ingredientItemId").asLong();
            RecipeIngredientValue value = find(values, ingredientItemId);

            assertThat(value.ingredientItemId())
                    .as("ingredientItemId %d", ingredientItemId)
                    .isEqualTo(legacyLine.path("ingredientItemId").asLong());
            assertThat(value.ingredientBarcode())
                    .as("barcode of %d", ingredientItemId)
                    .isEqualTo(legacyLine.path("ingredientBarcode").asText());
            assertThat(value.ingredientName())
                    .as("name of %d", ingredientItemId)
                    .isEqualTo(legacyLine.path("ingredientName").asText());
            assertThat(value.baseQuantity())
                    .as("baseQuantity of %d", ingredientItemId)
                    .isEqualTo(legacyLine.path("baseQuantity").asInt());
            assertThat(value.quantity())
                    .as("display quantity of %d", ingredientItemId)
                    .isEqualByComparingTo(legacyLine.path("quantity").decimalValue());
            assertThat(value.qtyUnit().name())
                    .as("qtyUnit of %d", ingredientItemId)
                    .isEqualTo(legacyLine.path("qtyUnit").asText());
        }

        // The characterized conversion values hold on both sides:
        // 2 PCS of NORMAL → base 2000; 100 G of WEIGHT(KG) → base 100, 0.1.
        assertThat(find(values, eggId).baseQuantity()).isEqualTo(2000);
        assertThat(find(values, cheeseId).baseQuantity()).isEqualTo(100);
        assertThat(find(values, cheeseId).quantity()).isEqualByComparingTo(new BigDecimal("0.1"));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private List<Long> ingredientIds(List<RecipeIngredientValue> values) {
        List<Long> ids = new ArrayList<>();
        for (RecipeIngredientValue value : values) {
            ids.add(value.ingredientItemId());
        }
        return ids;
    }

    private RecipeIngredientValue find(List<RecipeIngredientValue> values, long ingredientItemId) {
        for (RecipeIngredientValue value : values) {
            if (value.ingredientItemId() == ingredientItemId) {
                return value;
            }
        }
        throw new AssertionError("ingredient " + ingredientItemId + " missing from reader output");
    }

    private Item newIngredient(String name, ItemType itemType, MeasurementUnit defaultUnit) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("RCPI"))
                .name(name)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .reorderLevel(0)
                .itemType(itemType)
                .defaultUnit(defaultUnit)
                .build());
    }

    private Item newRecipe(String name) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("RCPR"))
                .name(name)
                .costPrice(new BigDecimal("0.00"))
                .sellingPrice(new BigDecimal("50.00"))
                .reorderLevel(0)
                .itemType(ItemType.RECIPE)
                .build());
    }

    /** Seeds one relationship row exactly as the table stores it (normalized base quantity). */
    private void link(Item recipe, Item ingredient, int baseQuantity) {
        recipeIngredientRepository.save(RecipeIngredient.builder()
                .parentItemId(recipe.getId())
                .ingredientId(ingredient.getId())
                .quantity(baseQuantity)
                .build());
    }

    private long createSubCategory() {
        // Same fixture pattern as ServiceRecipeProcessingResponseIntegrationTest:
        // category/sub-category rows are seeded through the repositories, items
        // through the public API so the real write path stores them.
        com.chala.posapp.entity.Category category = new com.chala.posapp.entity.Category();
        category.setName(uniqueKey("Rec category"));
        category = categoryRepository.save(category);

        com.chala.posapp.entity.SubCategory subCategory = new com.chala.posapp.entity.SubCategory();
        subCategory.setName(uniqueKey("Rec sub"));
        subCategory.setCategory(category);
        return subCategoryRepository.save(subCategory).getId();
    }
}
