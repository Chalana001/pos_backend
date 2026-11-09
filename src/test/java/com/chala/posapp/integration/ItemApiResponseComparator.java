package com.chala.posapp.integration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Explicit, field-by-field comparison between legacy item API responses and
 * future purpose-specific API responses. No reflection framework: the shared
 * contracts are written out as they exist today on {@code ItemResponse} and
 * its nested DTOs ({@code StockBatchResponse}, {@code ItemIngredientResponse},
 * {@code StockProcessingOutputLinkResponse}).
 *
 * <p>Ordering rules, taken from the production queries — not assumed:
 * <ul>
 *   <li>POS/purchase {@code batches}: ordered (PROCESSING first, then
 *       received time, then id) — compared as an exact sequence;</li>
 *   <li>{@code processingOutputs}: ordered ({@code findBySourceItemIdOrderByIdAsc})
 *       — compared as an exact sequence;</li>
 *   <li>{@code branchIds}: sorted ascending by the service mapping — compared
 *       as an exact sequence;</li>
 *   <li>{@code ingredients}: the repository query has no ORDER BY — matched by
 *       {@code ingredientItemId} as an unordered set;</li>
 *   <li>top-level item lists: no ordering contract — matched by item id.</li>
 * </ul>
 *
 * <p>Missing fields are never ignored silently. A field the candidate DTO
 * intentionally projects out must be named in the {@code intentionalOmissions}
 * argument; anything else missing fails the assertion. Fields that are
 * genuinely nondeterministic across runs ({@code createdAt} timestamps) are
 * excluded from comparison outright and are listed in
 * {@link #NONDETERMINISTIC_FIELDS}. Candidate-side extra fields are allowed
 * (a purpose DTO may add derived values).
 */
final class ItemApiResponseComparator {

    /** Proven nondeterministic across runs; excluded from every comparison. */
    static final Set<String> NONDETERMINISTIC_FIELDS = Set.of("createdAt");

    /** Scalar fields of ItemResponse shared by any purpose-specific DTO. */
    static final List<String> SHARED_ITEM_FIELDS = List.of(
            "id", "barcode", "name", "altName",
            "categoryId", "categoryName", "subCategoryId", "subCategoryName",
            "costPrice", "sellingPrice",
            "availableQty", "availableBaseQty", "hasNegativeStock",
            "reorderLevel", "reorderLevelBaseQty",
            "itemType", "defaultUnit", "imageUrl", "isKotEnabled",
            "active", "posVisible", "stockProcessingEnabled",
            "overheadCostMode", "overheadCostValue");

    /** Nested list fields compared by their dedicated rules. */
    static final List<String> NESTED_ITEM_FIELDS =
            List.of("branchIds", "batches", "ingredients", "processingOutputs");

    /** The fields PurchaseFormPage consumes after selecting a search result. */
    static final List<String> PURCHASE_ITEM_FIELDS = List.of(
            "id", "name", "altName", "barcode", "itemType",
            "costPrice", "sellingPrice", "defaultUnit",
            "availableQty", "availableBaseQty", "hasNegativeStock");

    private ItemApiResponseComparator() {
    }

    // ---------------------------------------------------------------
    // Item matching
    // ---------------------------------------------------------------

    /** Both lists must contain exactly the same item IDs; list order is not
     *  compared because neither API defines a top-level ordering contract. */
    static void assertSameItemIds(JsonNode legacyList, JsonNode candidateList) {
        assertThat(idsOf(legacyList))
                .as("item IDs (order-insensitive)")
                .containsExactlyInAnyOrderElementsOf(idsOf(candidateList));
    }

    /** Returns the item with the given id from a response list, failing when absent. */
    static JsonNode itemById(JsonNode list, long itemId) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == itemId) {
                return item;
            }
        }
        return fail("item " + itemId + " missing from response list");
    }

    private static List<Long> idsOf(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : list) {
            ids.add(item.path("id").asLong());
        }
        return ids;
    }

    // ---------------------------------------------------------------
    // Whole-item comparison
    // ---------------------------------------------------------------

    /**
     * Compares one legacy item against one candidate item field by field.
     *
     * @param intentionalOmissions fields the candidate DTO deliberately
     *        projects out. Any OTHER missing field is an accidental omission
     *        and fails. The special value "createdAt" is unnecessary here —
     *        nondeterministic fields are skipped outright.
     */
    static void assertSameItem(JsonNode legacy, JsonNode candidate, Set<String> intentionalOmissions) {
        Set<String> declared = new HashSet<>(intentionalOmissions);
        declared.addAll(NONDETERMINISTIC_FIELDS);

        for (String field : SHARED_ITEM_FIELDS) {
            if (declared.contains(field)) {
                continue;
            }
            if (!legacy.has(field)) {
                fail("legacy response is missing baseline field '" + field + "' — the baseline itself changed");
            }
            if (!candidate.has(field)) {
                fail("candidate response is missing field '" + field
                        + "'; declare it in intentionalOmissions if the DTO projection drops it on purpose");
            }
            assertNodeEquals("item." + field, legacy.get(field), candidate.get(field));
        }

        for (String field : NESTED_ITEM_FIELDS) {
            if (declared.contains(field)) {
                continue;
            }
            if (!legacy.has(field)) {
                fail("legacy response is missing baseline field '" + field + "' — the baseline itself changed");
            }
            if (!candidate.has(field)) {
                fail("candidate response is missing field '" + field
                        + "'; declare it in intentionalOmissions if the DTO projection drops it on purpose");
            }
            switch (field) {
                case "branchIds" -> assertSameBranchIds(legacy, candidate);
                case "batches" -> assertSamePositiveBatches(legacy, candidate);
                case "ingredients" -> assertSameIngredients(legacy, candidate);
                case "processingOutputs" -> assertSameProcessingOutputs(legacy, candidate);
                default -> throw new IllegalStateException("unhandled nested field " + field);
            }
        }
    }

    /** The stock triple every consumer reads: totals and negative-stock flag. */
    static void assertSameStock(JsonNode legacy, JsonNode candidate) {
        assertNodeEquals("item.availableQty", legacy.path("availableQty"), candidate.path("availableQty"));
        assertNodeEquals("item.availableBaseQty", legacy.path("availableBaseQty"), candidate.path("availableBaseQty"));
        assertNodeEquals("item.hasNegativeStock", legacy.path("hasNegativeStock"), candidate.path("hasNegativeStock"));
    }

    /** The exact fields PurchaseFormPage consumes after selecting a result. */
    static void assertSamePurchaseFields(JsonNode legacy, JsonNode candidate) {
        for (String field : PURCHASE_ITEM_FIELDS) {
            if (!legacy.has(field) || !candidate.has(field)) {
                fail("purchase field '" + field + "' missing from "
                        + (legacy.has(field) ? "candidate" : "legacy") + " response");
            }
            assertNodeEquals("item." + field, legacy.get(field), candidate.get(field));
        }
    }

    // ---------------------------------------------------------------
    // Nested structures
    // ---------------------------------------------------------------

    /**
     * Positive batch details, compared as an exact sequence: the production
     * query orders batches (PROCESSING first, received time, id) and consumers
     * rely on the first entry, so the order is part of the contract.
     */
    static void assertSamePositiveBatches(JsonNode legacy, JsonNode candidate) {
        JsonNode legacyBatches = legacy.path("batches");
        JsonNode candidateBatches = candidate.path("batches");
        assertThat(candidateBatches.size())
                .as("batch count (order matters)")
                .isEqualTo(legacyBatches.size());
        for (int i = 0; i < legacyBatches.size(); i++) {
            JsonNode legacyBatch = legacyBatches.get(i);
            JsonNode candidateBatch = candidateBatches.get(i);
            for (String field : List.of("batchId", "price", "qty", "displayQty", "displayUnit", "expiry")) {
                if (!candidateBatch.has(field)) {
                    fail("candidate batch[" + i + "] is missing field '" + field + "'");
                }
                assertNodeEquals("batches[" + i + "]." + field,
                        legacyBatch.get(field), candidateBatch.get(field));
            }
        }
    }

    /** Batch IDs only, still as an exact sequence (for projected batch DTOs). */
    static void assertSameBatchIds(JsonNode legacy, JsonNode candidate) {
        assertThat(longIdsOf(candidate.path("batches")))
                .as("positive batch IDs in returned order")
                .containsExactlyElementsOf(longIdsOf(legacy.path("batches")));
    }

    /**
     * Recipe ingredients, matched by ingredientItemId as an unordered set:
     * {@code RecipeIngredientRepository.findByParentItemId} declares no
     * ORDER BY, so ingredient order is not part of the contract.
     */
    static void assertSameIngredients(JsonNode legacy, JsonNode candidate) {
        Map<Long, JsonNode> legacyByItem = byIngredientItemId(legacy.path("ingredients"));
        Map<Long, JsonNode> candidateByItem = byIngredientItemId(candidate.path("ingredients"));
        assertThat(candidateByItem.keySet())
                .as("ingredient item IDs (unordered)")
                .containsExactlyInAnyOrderElementsOf(legacyByItem.keySet());
        for (Map.Entry<Long, JsonNode> entry : legacyByItem.entrySet()) {
            JsonNode legacyIngredient = entry.getValue();
            JsonNode candidateIngredient = candidateByItem.get(entry.getKey());
            for (String field : List.of(
                    "ingredientItemId", "ingredientBarcode", "ingredientName",
                    "quantity", "baseQuantity", "qtyUnit")) {
                if (!candidateIngredient.has(field)) {
                    fail("candidate ingredient " + entry.getKey() + " is missing field '" + field + "'");
                }
                assertNodeEquals("ingredients[" + entry.getKey() + "]." + field,
                        legacyIngredient.get(field), candidateIngredient.get(field));
            }
        }
    }

    /** Ingredient IDs only, unordered (for projected ingredient DTOs). */
    static void assertSameIngredientIds(JsonNode legacy, JsonNode candidate) {
        assertThat(byIngredientItemId(candidate.path("ingredients")).keySet())
                .as("ingredient item IDs (unordered)")
                .containsExactlyInAnyOrderElementsOf(
                        byIngredientItemId(legacy.path("ingredients")).keySet());
    }

    /**
     * Processing outputs, compared as an exact sequence:
     * {@code findBySourceItemIdOrderByIdAsc} guarantees insertion order and
     * the mapping preserves it.
     */
    static void assertSameProcessingOutputs(JsonNode legacy, JsonNode candidate) {
        JsonNode legacyOutputs = legacy.path("processingOutputs");
        JsonNode candidateOutputs = candidate.path("processingOutputs");
        assertThat(candidateOutputs.size())
                .as("processing output count (order matters)")
                .isEqualTo(legacyOutputs.size());
        for (int i = 0; i < legacyOutputs.size(); i++) {
            JsonNode legacyOutput = legacyOutputs.get(i);
            JsonNode candidateOutput = candidateOutputs.get(i);
            for (String field : List.of(
                    "outputItemId", "outputBarcode", "outputName", "itemType", "defaultUnit",
                    "defaultQty", "defaultQtyUnit", "defaultSellingPrice", "waste")) {
                if (!candidateOutput.has(field)) {
                    fail("candidate processingOutputs[" + i + "] is missing field '" + field + "'");
                }
                assertNodeEquals("processingOutputs[" + i + "]." + field,
                        legacyOutput.get(field), candidateOutput.get(field));
            }
        }
    }

    /** Service branch assignments, exact sequence: the mapping sorts ascending. */
    static void assertSameBranchIds(JsonNode legacy, JsonNode candidate) {
        assertThat(longIdsOf(candidate.path("branchIds")))
                .as("service branchIds in returned order (sorted ascending)")
                .containsExactlyElementsOf(longIdsOf(legacy.path("branchIds")));
    }

    // ---------------------------------------------------------------
    // Node equality — numeric values compared by value, not representation
    // ---------------------------------------------------------------

    static void assertNodeEquals(String path, JsonNode legacy, JsonNode candidate) {
        if (legacy == null || legacy.isMissingNode()) {
            fail("legacy response is missing '" + path + "' — the baseline itself changed");
        }
        if (candidate == null || candidate.isMissingNode()) {
            fail("candidate response is missing '" + path + "'");
        }
        if (legacy.isNull() || candidate.isNull()) {
            assertThat(candidate.isNull())
                    .as(path + " (null-ness)")
                    .isEqualTo(legacy.isNull());
            return;
        }
        if (legacy.isNumber() && candidate.isNumber()) {
            assertThat(candidate.decimalValue().compareTo(legacy.decimalValue()))
                    .as(path + " (numeric value: expected " + legacy + ", got " + candidate + ")")
                    .isZero();
            return;
        }
        assertThat(candidate)
                .as(path + " (expected " + legacy + ", got " + candidate + ")")
                .isEqualTo(legacy);
    }

    private static List<Long> longIdsOf(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode element : array) {
            ids.add(element.asLong());
        }
        return ids;
    }

    private static Map<Long, JsonNode> byIngredientItemId(JsonNode ingredients) {
        java.util.LinkedHashMap<Long, JsonNode> byItem = new java.util.LinkedHashMap<>();
        for (JsonNode ingredient : ingredients) {
            byItem.put(ingredient.path("ingredientItemId").asLong(), ingredient);
        }
        return byItem;
    }

    /** Exposed for tests that need the ID set of a top-level item list. */
    static Set<Long> idSet(JsonNode itemList) {
        return new LinkedHashSet<>(idsOf(itemList));
    }

    /** Positive batch IDs of one item, in the order the API returned them. */
    static List<Long> batchIdList(JsonNode item) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode batch : item.path("batches")) {
            ids.add(batch.path("batchId").asLong());
        }
        return ids;
    }

    /** Service branchIds of one item, in the order the API returned them. */
    static List<Long> branchIdList(JsonNode item) {
        return longIdsOf(item.path("branchIds"));
    }
}
