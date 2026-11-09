package com.chala.posapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@link LegacyItemApiFixture} seeds its dataset and that the
 * legacy POS and purchase endpoints yield stable, complete baseline responses
 * for it — the reference the future {@code /items/pos/catalogue} and
 * {@code /items/purchase-search} comparisons (and the purpose-DTO mappings)
 * will be made against.
 *
 * <p>All captures here run through the H2 LIKE fallback (two-character search
 * term). The MySQL FULLTEXT path is a separate verification item for a
 * Docker-enabled environment and is not claimed by these tests.
 */
class LegacyItemApiCompatibilityIntegrationTest extends ApiIntegrationTestSupport {

    private final LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);

    /** Every field ItemResponse serializes today, nested lists included. */
    private static final List<String> LEGACY_ENVELOPE_FIELDS = List.of(
            "id", "barcode", "name", "altName",
            "categoryId", "categoryName", "subCategoryId", "subCategoryName",
            "costPrice", "sellingPrice", "availableQty", "availableBaseQty",
            "hasNegativeStock", "reorderLevel", "reorderLevelBaseQty",
            "itemType", "defaultUnit", "imageUrl", "isKotEnabled",
            "active", "posVisible", "stockProcessingEnabled",
            "overheadCostMode", "overheadCostValue", "createdAt",
            "branchIds", "ingredients", "processingOutputs", "batches",
            "labelExpiry", "scaleResolvedQuantity", "scaleResolvedUnit",
            "scaleResolvedAmount");

    // ---------------------------------------------------------------
    // 1. Fixture seeds its dataset
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the fixture seeds every documented row: 6 items, 6 batches across two branches")
    void fixtureSeedsEveryDatasetRow() throws Exception {
        LegacyItemApiFixture.Dataset d = fixture.seed("qa");

        for (long itemId : d.allItemIds()) {
            assertThat(itemRepository.findById(itemId)).isPresent();
        }
        // normal: +10,000 / 0 / -3,000 at main plus +5,000 at the second branch
        assertThat(stockBatchRepository.findByItemId(d.normalItemId())).hasSize(4);
        assertThat(stockBatchRepository.findByItemId(d.weightItemId())).hasSize(1);
        assertThat(stockBatchRepository.findByItemId(d.processedItemId())).hasSize(1);
        assertThat(stockBatchRepository.findByItemId(d.noStockItemId())).isEmpty();

        assertThat(stockBatchRepository.getTotalQuantityByItemAndBranch(d.mainBranchId(), d.normalItemId()))
                .isEqualTo(7000);
        assertThat(stockBatchRepository.getTotalQuantityByItemAndBranch(d.secondBranchId(), d.normalItemId()))
                .isEqualTo(5000);

        // Batch rows carry the seeded base quantities.
        assertThat(stockBatchRepository.findById(d.normalPositiveBatchId()).orElseThrow().getQuantity())
                .isEqualTo(10000);
        assertThat(stockBatchRepository.findById(d.normalZeroBatchId()).orElseThrow().getQuantity())
                .isZero();
        assertThat(stockBatchRepository.findById(d.normalNegativeBatchId()).orElseThrow().getQuantity())
                .isEqualTo(-3000);
        assertThat(stockBatchRepository.findById(d.weightBatchId()).orElseThrow().getQuantity())
                .isEqualTo(2500);
    }

    // ---------------------------------------------------------------
    // 2 + 5 + 6. POS baseline, required fields, nested structures
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POS baseline returns the expected eligible items with a complete envelope")
    void posBaselineReturnsExpectedItemsAndEnvelope() throws Exception {
        LegacyItemApiFixture.Dataset d = fixture.seed("qa");
        JsonNode pos = fixture.posBaseline(d, d.mainBranchId());

        // 4. identities match the expected eligible set (never-stocked item
        // is filtered out at a concrete branch).
        assertThat(ItemApiResponseComparator.idSet(pos))
                .containsExactlyInAnyOrderElementsOf(d.posEligibleAtMain());

        // 5. every item carries the full legacy envelope.
        for (JsonNode item : fixture.scopedToDataset(pos, d)) {
            for (String field : LEGACY_ENVELOPE_FIELDS) {
                assertThat(item.has(field))
                        .as("legacy envelope field '" + field + "' on item " + item.path("id").asLong())
                        .isTrue();
            }
        }

        // Stock triples exactly as the seeded ledger computes them.
        JsonNode normal = ItemApiResponseComparator.itemById(pos, d.normalItemId());
        ItemApiResponseComparator.assertSameStock(normal, normal);
        assertThat(normal.path("availableBaseQty").asInt()).isEqualTo(7000);
        assertThat(normal.path("availableQty").decimalValue()).isEqualByComparingTo("7");
        assertThat(normal.path("hasNegativeStock").asBoolean()).isTrue();

        JsonNode weight = ItemApiResponseComparator.itemById(pos, d.weightItemId());
        assertThat(weight.path("availableBaseQty").asInt()).isEqualTo(2500);
        assertThat(weight.path("availableQty").decimalValue()).isEqualByComparingTo("2.5");

        // 6. nested structures captured: positive batches only, exact order.
        assertThat(ItemApiResponseComparator.batchIdList(normal))
                .containsExactly(d.normalPositiveBatchId());
        JsonNode batch = normal.path("batches").get(0);
        for (String field : List.of("batchId", "price", "qty", "displayQty", "displayUnit", "expiry")) {
            assertThat(batch.has(field)).as("batch field '" + field + "'").isTrue();
        }
        assertThat(batch.path("qty").asInt()).isEqualTo(10000);
        assertThat(batch.path("displayQty").decimalValue()).isEqualByComparingTo("10");
        assertThat(batch.path("displayUnit").asText()).isEqualTo("PCS");

        // Recipe ingredients: unordered set with conversion + identity fields.
        JsonNode recipe = ItemApiResponseComparator.itemById(pos, d.recipeItemId());
        assertThat(recipe.path("ingredients")).hasSize(2);
        JsonNode normalIngredient = ingredientOf(recipe, d.normalItemId());
        assertThat(normalIngredient.path("ingredientBarcode").asText()).isNotBlank();
        assertThat(normalIngredient.path("ingredientName").asText()).isEqualTo("Qx normal item");
        assertThat(normalIngredient.path("baseQuantity").asInt()).isEqualTo(2000);
        assertThat(normalIngredient.path("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(normalIngredient.path("qtyUnit").asText()).isEqualTo("PCS");
        JsonNode weightIngredient = ingredientOf(recipe, d.weightItemId());
        assertThat(weightIngredient.path("baseQuantity").asInt()).isEqualTo(100);
        assertThat(weightIngredient.path("quantity").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(weightIngredient.path("qtyUnit").asText()).isEqualTo("KG");

        // Processing outputs: ordered, price fallback + waste captured.
        JsonNode processed = ItemApiResponseComparator.itemById(pos, d.processedItemId());
        JsonNode outputs = processed.path("processingOutputs");
        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0).path("outputItemId").asLong()).isEqualTo(d.weightItemId());
        assertThat(outputs.get(0).path("defaultQty").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(outputs.get(0).path("defaultQtyUnit").asText()).isEqualTo("KG");
        assertThat(outputs.get(0).path("defaultSellingPrice").decimalValue()).isEqualByComparingTo("200");
        assertThat(outputs.get(0).path("waste").asBoolean()).isTrue();
        assertThat(outputs.get(1).path("outputItemId").asLong()).isEqualTo(d.normalItemId());
        assertThat(outputs.get(1).path("defaultQty").decimalValue()).isEqualByComparingTo("3");
        assertThat(outputs.get(1).path("defaultSellingPrice").decimalValue()).isEqualByComparingTo("30");
        assertThat(outputs.get(1).path("waste").asBoolean()).isFalse();

        // Service branch assignments: sorted ascending.
        JsonNode service = ItemApiResponseComparator.itemById(pos, d.serviceItemId());
        assertThat(ItemApiResponseComparator.branchIdList(service))
                .containsExactly(d.mainBranchId(), d.secondBranchId());
    }

    // ---------------------------------------------------------------
    // 3. Purchase baseline
    // ---------------------------------------------------------------

    @Test
    @DisplayName("purchase baseline returns the stock-tracked items with purchase fields and stock triples")
    void purchaseBaselineReturnsExpectedItems() throws Exception {
        LegacyItemApiFixture.Dataset d = fixture.seed("qa");
        JsonNode purchase = fixture.purchaseBaseline(d, d.mainBranchId());

        // Service and recipe items are excluded; never-stocked is included.
        assertThat(ItemApiResponseComparator.idSet(purchase))
                .containsExactlyInAnyOrderElementsOf(d.purchaseEligible());

        for (JsonNode item : fixture.scopedToDataset(purchase, d)) {
            for (String field : ItemApiResponseComparator.PURCHASE_ITEM_FIELDS) {
                assertThat(item.has(field))
                        .as("purchase field '" + field + "' on item " + item.path("id").asLong())
                        .isTrue();
            }
        }

        JsonNode normal = ItemApiResponseComparator.itemById(purchase, d.normalItemId());
        assertThat(normal.path("availableBaseQty").asInt()).isEqualTo(7000);
        assertThat(normal.path("availableQty").decimalValue()).isEqualByComparingTo("7");
        assertThat(normal.path("hasNegativeStock").asBoolean()).isTrue();

        // The never-stocked item aggregates to zero (no exclusion here).
        JsonNode noStock = ItemApiResponseComparator.itemById(purchase, d.noStockItemId());
        assertThat(noStock.path("availableBaseQty").asInt()).isZero();
        assertThat(noStock.path("availableQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(noStock.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // Branch-scope behaviour of the POS baseline
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POS baseline eligibility and aggregates change with the branch scope")
    void posBaselineDiffersByBranchScope() throws Exception {
        LegacyItemApiFixture.Dataset d = fixture.seed("qa");

        JsonNode atMain = fixture.posBaseline(d, d.mainBranchId());
        JsonNode atSecond = fixture.posBaseline(d, d.secondBranchId());
        JsonNode all = fixture.posBaseline(d, 0L);
        JsonNode unscoped = fixture.posBaseline(d, null);

        assertThat(ItemApiResponseComparator.idSet(atSecond))
                .containsExactlyInAnyOrderElementsOf(d.posEligibleAtSecond());
        assertThat(ItemApiResponseComparator.idSet(all))
                .containsExactlyInAnyOrderElementsOf(d.posEligibleAllBranches());
        assertThat(ItemApiResponseComparator.idSet(unscoped))
                .containsExactlyInAnyOrderElementsOf(d.posEligibleUnscoped());

        // branchId=0 aggregates every branch: 10,000 + 0 - 3,000 + 5,000.
        JsonNode allNormal = ItemApiResponseComparator.itemById(all, d.normalItemId());
        assertThat(allNormal.path("availableBaseQty").asInt()).isEqualTo(12000);
        assertThat(allNormal.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(ItemApiResponseComparator.batchIdList(allNormal))
                .containsExactly(d.normalPositiveBatchId(), d.normalSecondBatchId());

        // A concrete branch only sees its own rows.
        JsonNode secondNormal = ItemApiResponseComparator.itemById(atSecond, d.normalItemId());
        assertThat(secondNormal.path("availableBaseQty").asInt()).isEqualTo(5000);
        assertThat(secondNormal.path("hasNegativeStock").asBoolean()).isFalse();

        // The unscoped call skips the stock block entirely.
        JsonNode unscopedNormal = ItemApiResponseComparator.itemById(unscoped, d.normalItemId());
        assertThat(unscopedNormal.path("availableBaseQty").isNull()).isTrue();
        assertThat(unscopedNormal.path("availableQty").isNull()).isTrue();
        assertThat(unscopedNormal.path("batches")).isEmpty();
        assertThat(unscopedNormal.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // 7. Repeated executions with equivalent seeds
    // ---------------------------------------------------------------

    @Test
    @DisplayName("two equivalent seeds produce equivalent normalized POS and purchase results")
    void repeatedSeedsProduceEquivalentNormalizedResults() throws Exception {
        LegacyItemApiFixture.Dataset first = fixture.seed("qa");
        LegacyItemApiFixture.Dataset second = fixture.seed("qb");

        JsonNode firstPos = fixture.normalize(fixture.posBaseline(first, first.mainBranchId()), first);
        JsonNode secondPos = fixture.normalize(fixture.posBaseline(second, second.mainBranchId()), second);
        assertThat(firstPos).isEqualTo(secondPos);

        JsonNode firstPurchase =
                fixture.normalize(fixture.purchaseBaseline(first, first.mainBranchId()), first);
        JsonNode secondPurchase =
                fixture.normalize(fixture.purchaseBaseline(second, second.mainBranchId()), second);
        assertThat(firstPurchase).isEqualTo(secondPurchase);
    }

    // ---------------------------------------------------------------
    // Comparison helper exercised on legacy responses + a test projection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the comparison helper matches legacy responses and accepts only declared projections")
    void comparatorMatchesLegacyAndOnlyDeclaredProjections() throws Exception {
        LegacyItemApiFixture.Dataset d = fixture.seed("qa");
        JsonNode pos = fixture.posBaseline(d, d.mainBranchId());
        JsonNode recipe = ItemApiResponseComparator.itemById(pos, d.recipeItemId());

        // Identity comparison of a legacy response against itself.
        ItemApiResponseComparator.assertSameItem(recipe, recipe, Set.of());
        ItemApiResponseComparator.assertSamePositiveBatches(recipe, recipe);
        ItemApiResponseComparator.assertSameIngredients(recipe, recipe);
        ItemApiResponseComparator.assertSameProcessingOutputs(recipe, recipe);
        ItemApiResponseComparator.assertSameBranchIds(recipe, recipe);

        // A "purpose DTO" projection: drop two fields on purpose and declare them.
        ObjectNode projection = recipe.deepCopy();
        projection.remove("ingredients");
        projection.remove("imageUrl");
        ItemApiResponseComparator.assertSameItem(recipe, projection, Set.of("ingredients", "imageUrl"));

        // The same projection without the declaration is an accidental
        // missing property and must fail, naming the field.
        ObjectNode broken = recipe.deepCopy();
        broken.remove("ingredients");
        assertThatThrownBy(() -> ItemApiResponseComparator.assertSameItem(recipe, broken, Set.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ingredients");

        // Value drift on a shared field must fail even when the envelope matches.
        ObjectNode drifted = recipe.deepCopy();
        drifted.put("sellingPrice", 999);
        assertThatThrownBy(() -> ItemApiResponseComparator.assertSameItem(recipe, drifted, Set.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sellingPrice");

        // Batch order is part of the contract: reversing the batch list fails.
        JsonNode all = fixture.posBaseline(d, 0L);
        JsonNode allNormal = ItemApiResponseComparator.itemById(all, d.normalItemId());
        ObjectNode reversed = allNormal.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode batchRows =
                (com.fasterxml.jackson.databind.node.ArrayNode) reversed.get("batches");
        JsonNode first = batchRows.get(0);
        batchRows.set(0, batchRows.get(1));
        batchRows.set(1, first);
        assertThatThrownBy(() -> ItemApiResponseComparator.assertSamePositiveBatches(allNormal, reversed))
                .isInstanceOf(AssertionError.class);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private JsonNode ingredientOf(JsonNode recipe, long ingredientItemId) {
        for (JsonNode ingredient : recipe.path("ingredients")) {
            if (ingredient.path("ingredientItemId").asLong() == ingredientItemId) {
                return ingredient;
            }
        }
        throw new AssertionError("ingredient " + ingredientItemId + " missing from recipe response");
    }
}
