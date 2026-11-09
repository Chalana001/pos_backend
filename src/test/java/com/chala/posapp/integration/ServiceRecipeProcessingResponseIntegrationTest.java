package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.BranchServiceItem;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.repository.BranchServiceItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the nested {@code ItemResponse} mapping performed by
 * {@code ItemService.mapToResponse} for SERVICE items (branch assignments),
 * RECIPE items (ingredients) and stock-processing items (output links), exactly
 * as it behaves today. These tests are a regression net for the planned shared
 * bulk-read services and DTO mapping work: the observable nested fields must
 * stay identical.
 *
 * <p>Searches use two-character terms against {@code /items/searchForPos}
 * because longer terms run the MySQL FULLTEXT path, which H2 cannot execute
 * (same convention as {@code SearchForPosStockSemanticsIntegrationTest}).
 * {@code /items/search} (LIKE-based) and {@code /items/{id}} work at any
 * length and are used to cross-check that the nested mapping does not depend
 * on a single endpoint.
 *
 * <p>Ingredient ordering is asserted unordered on purpose: the repository
 * query ({@code findByParentItemId}) declares no ORDER BY. Branch-id and
 * processing-output ordering ARE guaranteed by the source and asserted
 * exactly.
 */
class ServiceRecipeProcessingResponseIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private BranchServiceItemRepository branchServiceItemRepository;

    // ---------------------------------------------------------------
    // A. Active service assignment
    // ---------------------------------------------------------------

    @Test
    @DisplayName("active service assignment makes the item eligible at that branch and maps its response fields")
    void activeServiceAssignmentIsEligibleAndMapsFields() throws Exception {
        Shop shop = shop("svc-active");
        Item service = createServiceItem(shop, "Qa delivery fee", shop.branch().getId());

        JsonNode posItem = pick(posSearch(shop, "Qa", shop.branch().getId()), service.getId());
        assertThat(posItem.path("itemType").asText()).isEqualTo("SERVICE");
        assertThat(posItem.path("defaultUnit").asText()).isEqualTo("SERVICE");
        assertThat(branchIds(posItem)).containsExactly(shop.branch().getId());
        // Service items never enter the stock block: no quantity, no batches,
        // no negative flag.
        assertThat(posItem.path("availableBaseQty").isNull()).isTrue();
        assertThat(posItem.path("availableQty").isNull()).isTrue();
        assertThat(posItem.path("batches")).isEmpty();
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(posItem.path("ingredients")).isEmpty();
        assertThat(posItem.path("processingOutputs")).isEmpty();

        JsonNode fetched = fetchItem(shop, service.getId());
        assertThat(branchIds(fetched)).containsExactly(shop.branch().getId());
    }

    // ---------------------------------------------------------------
    // B. Inactive service assignment
    // ---------------------------------------------------------------

    @Test
    @DisplayName("inactive service assignment hides the item at that branch and drops it from branchIds")
    void inactiveServiceAssignmentIsNotEligible() throws Exception {
        Shop shop = shop("svc-inactive");
        Item service = createServiceItem(shop, "Qb parking fee", shop.branch().getId());
        setAssignmentActive(service.getId(), shop.branch().getId(), false);

        // Eligibility uses existsByBranchIdAndItemIdAndActiveTrue, so an
        // inactive row is treated exactly like no row at all.
        assertThat(containsItem(posSearch(shop, "Qb", shop.branch().getId()), service.getId())).isFalse();

        // mapToResponse filters branchIds to active assignments only.
        JsonNode fetched = fetchItem(shop, service.getId());
        assertThat(branchIds(fetched)).isEmpty();
    }

    // ---------------------------------------------------------------
    // C. Unassigned service at the requested branch
    // ---------------------------------------------------------------

    @Test
    @DisplayName("service assigned to another branch is not eligible at the requested branch")
    void unassignedServiceIsNotEligibleForConcreteBranch() throws Exception {
        Shop shop = shop("svc-unassigned");
        Branch other = createBranch("COTH-" + uniqueKey("B"));
        Item service = createServiceItem(shop, "Qc corkage fee", other.getId());

        assertThat(containsItem(posSearch(shop, "Qc", shop.branch().getId()), service.getId())).isFalse();
        assertThat(containsItem(posSearch(shop, "Qc", other.getId()), service.getId())).isTrue();

        JsonNode fetched = fetchItem(shop, service.getId());
        assertThat(branchIds(fetched)).containsExactly(other.getId());
    }

    // ---------------------------------------------------------------
    // D. Multiple branches
    // ---------------------------------------------------------------

    @Test
    @DisplayName("service branchIds are active-only, de-duplicated and sorted ascending")
    void serviceBranchIdsAreActiveDistinctAndSorted() throws Exception {
        Shop shop = shop("svc-multi");
        Branch first = createBranch("D1-" + uniqueKey("B"));
        Branch second = createBranch("D2-" + uniqueKey("B"));
        Branch third = createBranch("D3-" + uniqueKey("B"));
        // Request order is scrambled and contains a duplicate; ids ascend with
        // creation order first < second < third.
        Item service = createServiceItem(
                shop, "Qd multi branch fee", third.getId(), first.getId(), second.getId(), third.getId());

        JsonNode fetched = fetchItem(shop, service.getId());
        // .sorted() in mapToResponse makes ascending id order an explicit
        // guarantee regardless of request order.
        assertThat(branchIds(fetched)).containsExactly(first.getId(), second.getId(), third.getId());

        setAssignmentActive(service.getId(), second.getId(), false);
        JsonNode afterInactive = fetchItem(shop, service.getId());
        assertThat(branchIds(afterInactive)).containsExactly(first.getId(), third.getId());

        assertThat(containsItem(posSearch(shop, "Qd", second.getId()), service.getId())).isFalse();
        assertThat(containsItem(posSearch(shop, "Qd", third.getId()), service.getId())).isTrue();
    }

    // ---------------------------------------------------------------
    // E. Branch scope: concrete branch vs branchId=0 vs branchId=null
    // ---------------------------------------------------------------

    @Test
    @DisplayName("service eligibility: concrete branch enforces the assignment, branchId=0 and null skip the check")
    void serviceBranchScopeConcreteZeroAndNull() throws Exception {
        Shop shop = shop("svc-scope");
        Branch other = createBranch("EOTH-" + uniqueKey("B"));
        Item service = createServiceItem(shop, "Qe service charge", shop.branch().getId());

        // Concrete branch with the active assignment: included.
        assertThat(containsItem(posSearch(shop, "Qe", shop.branch().getId()), service.getId())).isTrue();
        // Concrete branch without an assignment: excluded.
        assertThat(containsItem(posSearch(shop, "Qe", other.getId()), service.getId())).isFalse();

        // branchId=0 skips the assignment check (guard is branchId != 0L), so
        // the item is returned even though this scope is not a branch.
        JsonNode atZero = pick(posSearch(shop, "Qe", 0L), service.getId());
        assertThat(branchIds(atZero)).containsExactly(shop.branch().getId());

        // branchId=null skips the check as well (guard is branchId != null).
        JsonNode atNull = pick(posSearch(shop, "Qe", null), service.getId());
        assertThat(branchIds(atNull)).containsExactly(shop.branch().getId());
    }

    // ---------------------------------------------------------------
    // Recipe items
    // ---------------------------------------------------------------

    @Test
    @DisplayName("recipe ingredients map identity fields, base/display quantities and the ingredient's default unit")
    void recipeIngredientsMapIdentityQuantitiesAndUnits() throws Exception {
        Shop shop = shop("recipe-map");
        Item egg = createNormalItem(shop, "Qf farm egg", "20", "30");
        Item cheese = createWeightItem(shop, "Qg cheese", "1000", "1400");
        Item recipe = createRecipeItem(shop, "Qh cheese omelette", """
                [
                  {"ingredientItemId": %d, "quantity": 2},
                  {"ingredientItemId": %d, "quantity": 100, "qtyUnit": "G"}
                ]
                """.formatted(egg.getId(), cheese.getId()));

        JsonNode fetched = fetchItem(shop, recipe.getId());
        JsonNode ingredients = fetched.path("ingredients");
        assertThat(ingredientIds(ingredients)).containsExactlyInAnyOrder(egg.getId(), cheese.getId());

        // NORMAL ingredient requested as 2 (PCS, unit omitted): stored in
        // milli-pieces, returned as display pieces with the item's default unit.
        JsonNode eggLine = findIngredient(ingredients, egg.getId());
        assertThat(eggLine.path("ingredientItemId").asLong()).isEqualTo(egg.getId());
        assertThat(eggLine.path("ingredientBarcode").asText()).isEqualTo(egg.getBarcode());
        assertThat(eggLine.path("ingredientName").asText()).isEqualTo("Qf farm egg");
        assertThat(eggLine.path("baseQuantity").asInt()).isEqualTo(2000);
        assertThat(eggLine.path("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(eggLine.path("qtyUnit").asText()).isEqualTo("PCS");

        // WEIGHT ingredient requested as 100 G: stored as 100 grams, returned
        // as 0.1 in the ingredient's default unit KG — the response unit is the
        // ingredient item's defaultUnit, not the requested G.
        JsonNode cheeseLine = findIngredient(ingredients, cheese.getId());
        assertThat(cheeseLine.path("baseQuantity").asInt()).isEqualTo(100);
        assertThat(cheeseLine.path("quantity").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(cheeseLine.path("qtyUnit").asText()).isEqualTo("KG");
    }

    @Test
    @DisplayName("recipe without ingredients maps an empty ingredient list")
    void recipeWithoutIngredientsMapsEmptyList() throws Exception {
        Shop shop = shop("recipe-empty");
        Item recipe = createRecipeItem(shop, "Qi plain recipe", "[]");

        JsonNode fetched = fetchItem(shop, recipe.getId());
        assertThat(fetched.path("ingredients")).isEmpty();
    }

    @Test
    @DisplayName("recipe stock fields stay null and its POS batches empty like other non-stock items")
    void recipeStockFieldsAndBatchesAreEmpty() throws Exception {
        Shop shop = shop("recipe-stock");
        Item egg = createNormalItem(shop, "Qj egg", "20", "30");
        Item recipe = createRecipeItem(shop, "Qk omelette", """
                [{"ingredientItemId": %d, "quantity": 2}]
                """.formatted(egg.getId()));

        JsonNode posItem = pick(posSearch(shop, "Qk", shop.branch().getId()), recipe.getId());
        assertThat(posItem.path("availableBaseQty").isNull()).isTrue();
        assertThat(posItem.path("availableQty").isNull()).isTrue();
        assertThat(posItem.path("batches")).isEmpty();
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isFalse();
        // ... while the ingredients still come through the same search response.
        assertThat(ingredientIds(posItem.path("ingredients"))).containsExactly(egg.getId());
    }

    // ---------------------------------------------------------------
    // Processing outputs
    // ---------------------------------------------------------------

    @Test
    @DisplayName("processing outputs map identity fields, converted quantities, price fallback and waste flag")
    void processingOutputsMapIdentityQuantityPriceAndWaste() throws Exception {
        Shop shop = shop("proc-map");
        Item flour = createWeightItem(shop, "Ql flour", "100", "150");
        Item normal = createNormalItem(shop, "Qm crumb", "5", "9");
        Item source = createProcessingSource(shop, "Qn wheat", """
                [
                  {"outputItemId": %d, "defaultQty": 1.5, "defaultSellingPrice": 200, "waste": true},
                  {"outputItemId": %d, "defaultQty": 3}
                ]
                """.formatted(flour.getId(), normal.getId()));

        JsonNode outputs = fetchItem(shop, source.getId()).path("processingOutputs");
        assertThat(outputIds(outputs)).containsExactly(flour.getId(), normal.getId());

        // First link: explicit values. WEIGHT output stores 1.5 KG as 1500 g
        // and reports 1.5 with the output's primary display unit KG.
        JsonNode flourLine = findOutput(outputs, flour.getId());
        assertThat(flourLine.path("outputItemId").asLong()).isEqualTo(flour.getId());
        assertThat(flourLine.path("outputBarcode").asText()).isEqualTo(flour.getBarcode());
        assertThat(flourLine.path("outputName").asText()).isEqualTo("Ql flour");
        assertThat(flourLine.path("itemType").asText()).isEqualTo("WEIGHT");
        assertThat(flourLine.path("defaultUnit").asText()).isEqualTo("KG");
        assertThat(flourLine.path("defaultQty").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(flourLine.path("defaultQtyUnit").asText()).isEqualTo("KG");
        assertThat(flourLine.path("defaultSellingPrice").decimalValue()).isEqualByComparingTo("200");
        assertThat(flourLine.path("waste").asBoolean()).isTrue();

        // Second link: defaults and fallback. NORMAL output stores 3 PCS as
        // 3000 milli-pieces, price falls back to the output item's selling
        // price (9), waste falls back to false.
        JsonNode normalLine = findOutput(outputs, normal.getId());
        assertThat(normalLine.path("itemType").asText()).isEqualTo("NORMAL");
        assertThat(normalLine.path("defaultUnit").asText()).isEqualTo("PCS");
        assertThat(normalLine.path("defaultQty").decimalValue()).isEqualByComparingTo("3");
        assertThat(normalLine.path("defaultQtyUnit").asText()).isEqualTo("PCS");
        assertThat(normalLine.path("defaultSellingPrice").decimalValue()).isEqualByComparingTo("9");
        assertThat(normalLine.path("waste").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("processing source without the flag maps an empty output list")
    void processingSourceWithoutFlagMapsEmptyOutputs() throws Exception {
        Shop shop = shop("proc-off");
        Item plain = createNormalItem(shop, "Qo plain item", "10", "20");

        JsonNode fetched = fetchItem(shop, plain.getId());
        assertThat(fetched.path("stockProcessingEnabled").asBoolean()).isFalse();
        assertThat(fetched.path("processingOutputs")).isEmpty();
    }

    // ---------------------------------------------------------------
    // Shared ItemResponse compatibility
    // ---------------------------------------------------------------

    @Test
    @DisplayName("nested mapping is identical across item search and item-by-id endpoints")
    void nestedMappingIsConsistentAcrossEndpoints() throws Exception {
        Shop shop = shop("shared");
        Item egg = createNormalItem(shop, "Qp egg", "20", "30");
        Item recipe = createRecipeItem(shop, "Qp omelette", """
                [{"ingredientItemId": %d, "quantity": 2}]
                """.formatted(egg.getId()));
        Item flour = createWeightItem(shop, "Qq flour", "100", "150");
        Item source = createProcessingSource(shop, "Qq wheat", """
                [{"outputItemId": %d, "defaultQty": 1.5}]
                """.formatted(flour.getId()));
        Item service = createServiceItem(shop, "Qr service fee", shop.branch().getId());

        // One nested field per item type, compared between the search
        // endpoint and the by-id endpoint: both go through the same
        // mapToResponse and must agree field for field.
        JsonNode recipeFromSearch = pick(posSearch(shop, "Qp", shop.branch().getId()), recipe.getId());
        JsonNode recipeFromId = fetchItem(shop, recipe.getId());
        assertThat(recipeFromSearch.path("ingredients")).isEqualTo(recipeFromId.path("ingredients"));

        JsonNode sourceFromSearch = pick(searchByName(shop, "Qq", shop.branch().getId()), source.getId());
        JsonNode sourceFromId = fetchItem(shop, source.getId());
        assertThat(sourceFromSearch.path("processingOutputs")).isEqualTo(sourceFromId.path("processingOutputs"));

        JsonNode serviceFromSearch = pick(posSearch(shop, "Qr", shop.branch().getId()), service.getId());
        JsonNode serviceFromId = fetchItem(shop, service.getId());
        assertThat(serviceFromSearch.path("branchIds")).isEqualTo(serviceFromId.path("branchIds"));

        // Plain item: all three nested lists are empty arrays, never null.
        JsonNode plainFromId = fetchItem(shop, egg.getId());
        assertThat(plainFromId.path("ingredients")).isEmpty();
        assertThat(plainFromId.path("processingOutputs")).isEmpty();
        assertThat(plainFromId.path("branchIds")).isEmpty();
    }

    // ---------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------

    private record Shop(TenantFixture fixture, String token, SubCategory subCategory) {
        String tenantId() {
            return fixture.tenantId();
        }

        Branch branch() {
            return fixture.mainBranch();
        }
    }

    private Shop shop(String prefix) throws Exception {
        TenantFixture fixture = seedTenantShop(uniqueKey(prefix), 3);
        String token = login(fixture.tenantId(), fixture.admin().getUsername(), DEFAULT_PASSWORD);

        Category category = new Category();
        category.setName(uniqueKey("Rsp category"));
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(uniqueKey("Rsp stock"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        return new Shop(fixture, token, subCategory);
    }

    private Item createNormalItem(Shop shop, String name, String costPrice, String sellingPrice) throws Exception {
        return createItem(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": %s,
                  "sellingPrice": %s,
                  "reorderLevel": 0
                }
                """.formatted(uniqueKey("RSP"), name, shop.subCategory().getId(), costPrice, sellingPrice));
    }

    private Item createWeightItem(Shop shop, String name, String costPrice, String sellingPrice) throws Exception {
        return createItem(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": %s,
                  "sellingPrice": %s,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(uniqueKey("RSP"), name, shop.subCategory().getId(), costPrice, sellingPrice));
    }

    private Item createServiceItem(Shop shop, String name, Long... branchIds) throws Exception {
        String ids = java.util.Arrays.stream(branchIds).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
        return createItem(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 100,
                  "reorderLevel": 0,
                  "itemType": "SERVICE",
                  "branchIds": [%s]
                }
                """.formatted(uniqueKey("RSP"), name, shop.subCategory().getId(), ids));
    }

    /** ingredientsJson is a JSON array literal of ItemIngredientRequest objects. */
    private Item createRecipeItem(Shop shop, String name, String ingredientsJson) throws Exception {
        return createItem(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 180,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "ingredients": %s
                }
                """.formatted(uniqueKey("RSP"), name, shop.subCategory().getId(), ingredientsJson));
    }

    /** outputsJson is a JSON array literal of StockProcessingOutputLinkRequest objects. */
    private Item createProcessingSource(Shop shop, String name, String outputsJson) throws Exception {
        return createItem(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 300,
                  "sellingPrice": 360,
                  "reorderLevel": 0,
                  "stockProcessingEnabled": true,
                  "processingOutputs": %s
                }
                """.formatted(uniqueKey("RSP"), name, shop.subCategory().getId(), outputsJson));
    }

    private Item createItem(Shop shop, String jsonBody) throws Exception {
        JsonNode created = postJson("/items", shop.tenantId(), shop.token(), jsonBody);
        return itemRepository.findById(created.path("id").asLong()).orElseThrow();
    }

    private Branch createBranch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(code + " branch");
        branch.setAddress("Colombo");
        branch.setPhone("0770000000");
        branch.setActive(true);
        return branchRepository.save(branch);
    }

    private void setAssignmentActive(long itemId, long branchId, boolean active) {
        BranchServiceItem assignment = branchServiceItemRepository.findByItemId(itemId).stream()
                .filter(row -> row.getBranchId().equals(branchId))
                .findFirst()
                .orElseThrow();
        assignment.setActive(active);
        branchServiceItemRepository.save(assignment);
    }

    private JsonNode fetchItem(Shop shop, long itemId) throws Exception {
        return getJson("/items/" + itemId, shop.tenantId(), shop.token());
    }

    /** branchId=null omits the query parameter, matching an unscoped call. */
    private JsonNode posSearch(Shop shop, String name, Long branchId) throws Exception {
        String url = "/items/searchForPos?name=" + name
                + (branchId != null ? "&branchId=" + branchId : "");
        return getJson(url, shop.tenantId(), shop.token());
    }

    /** The LIKE-based /items/search, used where FULLTEXT is not under test. */
    private JsonNode searchByName(Shop shop, String name, Long branchId) throws Exception {
        return getJson("/items/search?name=" + name + "&branchId=" + branchId,
                shop.tenantId(), shop.token());
    }

    private JsonNode pick(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return candidate;
            }
        }
        throw new AssertionError("item " + itemId + " missing from item search results");
    }

    private boolean containsItem(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return true;
            }
        }
        return false;
    }

    private List<Long> branchIds(JsonNode itemNode) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : itemNode.path("branchIds")) {
            ids.add(id.asLong());
        }
        return ids;
    }

    private List<Long> ingredientIds(JsonNode ingredientsNode) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode ingredient : ingredientsNode) {
            ids.add(ingredient.path("ingredientItemId").asLong());
        }
        return ids;
    }

    private List<Long> outputIds(JsonNode outputsNode) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode output : outputsNode) {
            ids.add(output.path("outputItemId").asLong());
        }
        return ids;
    }

    private JsonNode findIngredient(JsonNode ingredientsNode, long ingredientItemId) {
        for (JsonNode ingredient : ingredientsNode) {
            if (ingredient.path("ingredientItemId").asLong() == ingredientItemId) {
                return ingredient;
            }
        }
        throw new AssertionError("ingredient " + ingredientItemId + " missing from recipe response");
    }

    private JsonNode findOutput(JsonNode outputsNode, long outputItemId) {
        for (JsonNode output : outputsNode) {
            if (output.path("outputItemId").asLong() == outputItemId) {
                return output;
            }
        }
        throw new AssertionError("output " + outputItemId + " missing from processing response");
    }
}
