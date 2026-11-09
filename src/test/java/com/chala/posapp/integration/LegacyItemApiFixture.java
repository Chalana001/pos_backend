package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reusable legacy item-API fixture: seeds one deterministic dataset and
 * captures the current responses of {@code GET /items/searchForPos} and
 * {@code GET /items/searchForPurchase} for it. Future compatibility tests
 * (old endpoint vs new purpose-specific endpoint) reuse {@link #seed(String)},
 * the two {@code ...Baseline} captures, {@link #normalize(JsonNode, Dataset)}
 * and {@link ItemApiResponseComparator} instead of rebuilding the dataset.
 *
 * <p><b>Search path caveat.</b> The captures use a two-character search term
 * ({@link #SEARCH_TOKEN}) so the H2 suite exercises the LIKE fallback inside
 * {@code ItemService}. The MySQL FULLTEXT path ({@code searchForPosFt} /
 * {@code searchForPurchaseFt}, three or more characters) is NOT validated
 * here; it remains a separate verification item for a Docker-enabled MySQL
 * test environment (the {@code tc} Testcontainers suite).
 *
 * <p><b>Determinism.</b> Item names are fixed; every uniqueness-constrained
 * value (barcodes, category/subcategory/branch names and codes) carries the
 * caller's {@code runTag}, and the normalizer replaces those values with
 * stable aliases. Generated IDs are aliased to role names, and
 * {@code createdAt} timestamps are masked. Nothing else is thrown away.
 *
 * <p>Usage from a test that extends {@link ApiIntegrationTestSupport}:
 * <pre>{@code
 *   LegacyItemApiFixture fixture = new LegacyItemApiFixture(this);
 *   LegacyItemApiFixture.Dataset d = fixture.seed("qa");
 *   JsonNode pos = fixture.posBaseline(d, d.mainBranchId());
 * }</pre>
 */
final class LegacyItemApiFixture {

    /** Two characters: the H2-compatible LIKE fallback inside ItemService. */
    static final String SEARCH_TOKEN = "Qx";

    private final ApiIntegrationTestSupport support;

    LegacyItemApiFixture(ApiIntegrationTestSupport support) {
        this.support = support;
    }

    /**
     * Everything the fixture created. IDs come from the saved entities — the
     * fixture never hardcodes database IDs. The alias maps feed
     * {@link #normalize(JsonNode, Dataset)}.
     */
    record Dataset(
            String runTag,
            String tenantId,
            String adminToken,
            long mainBranchId,
            long secondBranchId,
            long categoryId,
            long subCategoryId,
            long normalItemId,
            long weightItemId,
            long serviceItemId,
            long recipeItemId,
            long processedItemId,
            long noStockItemId,
            long normalPositiveBatchId,
            long normalZeroBatchId,
            long normalNegativeBatchId,
            long normalSecondBatchId,
            long weightBatchId,
            long processedBatchId,
            Map<String, String> idAliases,
            Map<String, String> stringAliases) {

        /** POS-eligible at the main branch: stocked items plus service and recipe. */
        Set<Long> posEligibleAtMain() {
            return Set.of(normalItemId, weightItemId, serviceItemId, recipeItemId, processedItemId);
        }

        /** At the second branch only the items actually stocked there survive. */
        Set<Long> posEligibleAtSecond() {
            return Set.of(normalItemId, serviceItemId, recipeItemId);
        }

        /** branchId=0 aggregates all branches; never-stocked still excluded. */
        Set<Long> posEligibleAllBranches() {
            return Set.of(normalItemId, weightItemId, serviceItemId, recipeItemId, processedItemId);
        }

        /** Unscoped POS search skips the stock block: everything active appears. */
        Set<Long> posEligibleUnscoped() {
            return Set.of(normalItemId, weightItemId, serviceItemId, recipeItemId, processedItemId, noStockItemId);
        }

        /** Purchase search: stock-tracked only, and no never-stocked filter. */
        Set<Long> purchaseEligible() {
            return Set.of(normalItemId, weightItemId, processedItemId, noStockItemId);
        }

        Set<Long> allItemIds() {
            return Set.of(normalItemId, weightItemId, serviceItemId, recipeItemId, processedItemId, noStockItemId);
        }
    }

    // ---------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------

    /**
     * Seeds the deterministic dataset described in the class javadoc. The
     * runTag only disambiguates uniqueness-constrained values when two
     * datasets are seeded into one schema; it never leaks into item names.
     */
    Dataset seed(String runTag) throws Exception {
        ApiIntegrationTestSupport.TenantFixture tenant =
                support.seedTenantShop(support.uniqueKey("legacy-" + runTag), 3);
        String token = support.login(
                tenant.tenantId(), tenant.admin().getUsername(), ApiIntegrationTestSupport.DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName(support.uniqueKey("Qx category"));
        category = support.categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(support.uniqueKey("Qx subcategory"));
        subCategory.setCategory(category);
        subCategory = support.subCategoryRepository.save(subCategory);

        Branch second = new Branch();
        second.setCode(support.uniqueKey("QXB"));
        second.setName(support.uniqueKey("Qx second branch"));
        second.setAddress("Colombo");
        second.setPhone("0770000000");
        second.setActive(true);
        second = support.branchRepository.save(second);

        long mainId = tenant.mainBranch().getId();
        long secondId = second.getId();

        // --- items: fixed names, run-tag barcodes ---
        long normalId = createItem(tenant, token, """
                {"barcode": "%s-NRM", "name": "Qx normal item", "altName": "Qx alt normal",
                 "subCategoryId": %d, "costPrice": 20, "sellingPrice": 30, "reorderLevel": 5}
                """.formatted(runTag, subCategory.getId()));

        long weightId = createItem(tenant, token, """
                {"barcode": "%s-WGT", "name": "Qx weight item",
                 "subCategoryId": %d, "costPrice": 1000, "sellingPrice": 1400, "reorderLevel": 0,
                 "itemType": "WEIGHT", "defaultUnit": "KG"}
                """.formatted(runTag, subCategory.getId()));

        long noStockId = createItem(tenant, token, """
                {"barcode": "%s-NSK", "name": "Qx no stock item",
                 "subCategoryId": %d, "costPrice": 10, "sellingPrice": 20, "reorderLevel": 0}
                """.formatted(runTag, subCategory.getId()));

        long serviceId = createItem(tenant, token, """
                {"barcode": "%s-SRV", "name": "Qx service item",
                 "subCategoryId": %d, "costPrice": 0, "sellingPrice": 100, "reorderLevel": 0,
                 "itemType": "SERVICE", "branchIds": [%d, %d]}
                """.formatted(runTag, subCategory.getId(), mainId, secondId));

        long recipeId = createItem(tenant, token, """
                {"barcode": "%s-RCP", "name": "Qx recipe item",
                 "subCategoryId": %d, "costPrice": 0, "sellingPrice": 180, "reorderLevel": 0,
                 "itemType": "RECIPE", "ingredients": [
                   {"ingredientItemId": %d, "quantity": 2},
                   {"ingredientItemId": %d, "quantity": 100, "qtyUnit": "G"}
                 ]}
                """.formatted(runTag, subCategory.getId(), normalId, weightId));

        long processedId = createItem(tenant, token, """
                {"barcode": "%s-PRC", "name": "Qx processed item",
                 "subCategoryId": %d, "costPrice": 300, "sellingPrice": 360, "reorderLevel": 0,
                 "stockProcessingEnabled": true, "processingOutputs": [
                   {"outputItemId": %d, "defaultQty": 1.5, "defaultSellingPrice": 200, "waste": true},
                   {"outputItemId": %d, "defaultQty": 3}
                 ]}
                """.formatted(runTag, subCategory.getId(), weightId, normalId));

        // --- batches: positive / zero / negative at main, multi-branch ---
        long normalPositive = insertBatch(tenant.mainBranch(), normalId, 10000, StockBatchSourceType.PURCHASE);
        long normalZero = insertBatch(tenant.mainBranch(), normalId, 0, StockBatchSourceType.PURCHASE);
        long normalNegative = insertBatch(tenant.mainBranch(), normalId, -3000, StockBatchSourceType.OVERRIDE);
        long normalSecond = insertBatch(second, normalId, 5000, StockBatchSourceType.PURCHASE);
        long weightBatch = insertBatch(tenant.mainBranch(), weightId, 2500, StockBatchSourceType.PURCHASE);
        long processedBatch = insertBatch(tenant.mainBranch(), processedId, 4000, StockBatchSourceType.PURCHASE);

        // Alias keys are namespaced by entity kind ("item:7", "batch:7",
        // "branch:3", ...): item, batch, branch and category IDs come from
        // independent identity columns and collide numerically, so a flat
        // Long-keyed map would alias one entity's ID to another's role.
        Map<String, String> idAliases = new HashMap<>();
        idAliases.put("branch:" + mainId, "branch:main");
        idAliases.put("branch:" + secondId, "branch:second");
        idAliases.put("category:" + category.getId(), "category");
        idAliases.put("subcategory:" + subCategory.getId(), "subcategory");
        idAliases.put("item:" + normalId, "item:normal");
        idAliases.put("item:" + weightId, "item:weight");
        idAliases.put("item:" + serviceId, "item:service");
        idAliases.put("item:" + recipeId, "item:recipe");
        idAliases.put("item:" + processedId, "item:processed");
        idAliases.put("item:" + noStockId, "item:no-stock");
        idAliases.put("batch:" + normalPositive, "batch:normal-positive");
        idAliases.put("batch:" + normalZero, "batch:normal-zero");
        idAliases.put("batch:" + normalNegative, "batch:normal-negative");
        idAliases.put("batch:" + normalSecond, "batch:normal-second");
        idAliases.put("batch:" + weightBatch, "batch:weight");
        idAliases.put("batch:" + processedBatch, "batch:processed");

        Map<String, String> stringAliases = new HashMap<>();
        stringAliases.put(runTag + "-NRM", "barcode:normal");
        stringAliases.put(runTag + "-WGT", "barcode:weight");
        stringAliases.put(runTag + "-NSK", "barcode:no-stock");
        stringAliases.put(runTag + "-SRV", "barcode:service");
        stringAliases.put(runTag + "-RCP", "barcode:recipe");
        stringAliases.put(runTag + "-PRC", "barcode:processed");
        stringAliases.put(category.getName(), "category:name");
        stringAliases.put(subCategory.getName(), "subcategory:name");

        return new Dataset(runTag, tenant.tenantId(), token, mainId, secondId,
                category.getId(), subCategory.getId(),
                normalId, weightId, serviceId, recipeId, processedId, noStockId,
                normalPositive, normalZero, normalNegative, normalSecond, weightBatch, processedBatch,
                Map.copyOf(idAliases), Map.copyOf(stringAliases));
    }

    private long createItem(ApiIntegrationTestSupport.TenantFixture tenant, String token, String body)
            throws Exception {
        return support.postJson("/items", tenant.tenantId(), token, body).path("id").asLong();
    }

    /** Seeds one batch row in base units (NORMAL: 1000 = 1 piece; WEIGHT: grams). */
    private long insertBatch(Branch branch, long itemId, int baseQuantity, StockBatchSourceType sourceType) {
        return support.stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(support.itemRepository.findById(itemId).orElseThrow())
                .quantity(baseQuantity)
                .originalQuantity(Math.abs(baseQuantity))
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .sourceType(sourceType)
                .batchCode(support.uniqueKey("SEED"))
                .build()).getId();
    }

    // ---------------------------------------------------------------
    // Legacy endpoint captures
    // ---------------------------------------------------------------

    /**
     * POS baseline: {@code GET /items/searchForPos?name=Qx&branchId=...}.
     * branchId null omits the parameter (the unscoped behaviour); 0 is the
     * all-branch scope.
     */
    JsonNode posBaseline(Dataset dataset, Long branchId) throws Exception {
        String url = "/items/searchForPos?name=" + SEARCH_TOKEN
                + (branchId != null ? "&branchId=" + branchId : "");
        return support.getJson(url, dataset.tenantId(), dataset.adminToken());
    }

    /**
     * Purchase baseline: {@code GET /items/searchForPurchase?name=Qx&branchId=...}.
     * Same branchId semantics as {@link #posBaseline(Dataset, Long)}.
     */
    JsonNode purchaseBaseline(Dataset dataset, Long branchId) throws Exception {
        String url = "/items/searchForPurchase?name=" + SEARCH_TOKEN
                + (branchId != null ? "&branchId=" + branchId : "");
        return support.getJson(url, dataset.tenantId(), dataset.adminToken());
    }

    // ---------------------------------------------------------------
    // Normalization (cross-seed equivalence only)
    // ---------------------------------------------------------------

    /**
     * Canonical form of a legacy item list, comparable across two equivalent
     * seeds of the same dataset. Rules, all deliberate:
     * <ul>
     *   <li>results are scoped to the dataset's own item IDs — a second seed
     *       in the same schema matches the same search token, and only the
     *       dataset under comparison is in scope;</li>
     *   <li>generated IDs become role aliases ({@code item:normal},
     *       {@code batch:weight}, ...);</li>
     *   <li>run-tag strings (barcodes, category/subcategory names) become
     *       stable aliases;</li>
     *   <li>{@code createdAt} timestamps are masked — they are the only
     *       genuinely nondeterministic values in the response;</li>
     *   <li>top-level items and recipe ingredients are sorted by their
     *       aliases because neither API defines an ordering contract for
     *       them; batch lists and processing outputs keep their returned
     *       order because the API does define it.</li>
     * </ul>
     * Everything else — names, prices, quantities, flags, nested structures —
     * is compared byte for byte.
     */
    JsonNode normalize(JsonNode response, Dataset dataset) {
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode item : scopedToDataset(response, dataset)) {
            items.add(normalizeItem(item, dataset));
        }
        items.sort(Comparator.comparing(n -> n.get("id").asText()));
        ArrayNode out = support.objectMapper.createArrayNode();
        items.forEach(out::add);
        return out;
    }

    /** Filters a response down to the items this dataset seeded. */
    List<JsonNode> scopedToDataset(JsonNode response, Dataset dataset) {
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : response) {
            if (dataset.allItemIds().contains(item.path("id").asLong())) {
                items.add(item);
            }
        }
        return items;
    }

    private static final Set<String> STRING_ALIAS_FIELDS =
            Set.of("barcode", "categoryName", "subCategoryName", "ingredientBarcode", "outputBarcode");

    private ObjectNode normalizeItem(JsonNode item, Dataset dataset) {
        ObjectNode out = support.objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            switch (name) {
                case "branchIds" -> {
                    ArrayNode ids = support.objectMapper.createArrayNode();
                    for (JsonNode id : value) {
                        ids.add(alias(dataset, "branch", id));
                    }
                    out.set(name, ids);
                }
                case "createdAt" -> out.put(name, "$ts");
                case "batches", "processingOutputs" -> {
                    ArrayNode rows = support.objectMapper.createArrayNode();
                    for (JsonNode row : value) {
                        rows.add(normalizeFlat(row, dataset));
                    }
                    out.set(name, rows);
                }
                case "ingredients" -> {
                    List<ObjectNode> rows = new ArrayList<>();
                    for (JsonNode row : value) {
                        rows.add(normalizeFlat(row, dataset));
                    }
                    rows.sort(Comparator.comparing(n -> n.get("ingredientItemId").asText()));
                    ArrayNode sorted = support.objectMapper.createArrayNode();
                    rows.forEach(sorted::add);
                    out.set(name, sorted);
                }
                default -> out.set(name, normalizeValue(name, value, dataset));
            }
        }
        return out;
    }

    private ObjectNode normalizeFlat(JsonNode row, Dataset dataset) {
        ObjectNode out = support.objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.set(field.getKey(), normalizeValue(field.getKey(), field.getValue(), dataset));
        }
        return out;
    }

    private JsonNode normalizeValue(String name, JsonNode value, Dataset dataset) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return value;
        }
        String idNamespace = idNamespaceFor(name);
        if (idNamespace != null && value.isNumber()) {
            return support.objectMapper.getNodeFactory().textNode(alias(dataset, idNamespace, value));
        }
        if ("createdAt".equals(name)) {
            return support.objectMapper.getNodeFactory().textNode("$ts");
        }
        if (STRING_ALIAS_FIELDS.contains(name) && value.isTextual()) {
            String text = value.asText();
            return support.objectMapper.getNodeFactory()
                    .textNode(dataset.stringAliases().getOrDefault(text, text));
        }
        return value;
    }

    /** Maps an ID-valued JSON field to its alias namespace, or null. */
    private static String idNamespaceFor(String name) {
        return switch (name) {
            case "id" -> "item";           // top-level item id
            case "batchId" -> "batch";
            case "ingredientItemId", "outputItemId" -> "item";
            case "categoryId" -> "category";
            case "subCategoryId" -> "subcategory";
            default -> null;
        };
    }

    private String alias(Dataset dataset, String namespace, JsonNode idValue) {
        return dataset.idAliases()
                .getOrDefault(namespace + ":" + idValue.asLong(), "id:" + idValue.asLong());
    }
}
