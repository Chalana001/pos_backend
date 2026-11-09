package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.repository.SupplierRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the purchase item search surface ({@code GET
 * /items/searchForPurchase} and the {@code GET /items/barcode/{barcode}}
 * fallback) exactly as it behaves today, ahead of the planned lightweight
 * {@code /items/purchase-search} API. The observable response contract that
 * {@code PurchaseFormPage.jsx} depends on must stay identical.
 *
 * <p>Search terms are deliberately one or two characters: terms of three
 * characters or more run through the MySQL FULLTEXT index
 * ({@code searchForPurchaseFt}), which H2 cannot execute. The one/two-character
 * LIKE fallback reaches the same eligibility and stock aggregation code, which
 * is what is under test here. The FULLTEXT path itself is exercised by the
 * MySQL/Testcontainers tests (e.g. {@code InventoryApiIntegrationTest}).
 *
 * <p>Quantities are asserted in base units (NORMAL items are stored in
 * thousandths of a piece: 10 display = 10,000 base), see
 * {@code QuantityConversionUtil}.
 */
class PurchaseSearchBehaviorIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SupplierRepository supplierRepository;

    // ---------------------------------------------------------------
    // A. Ordinary stock-tracked item + response field audit
    // ---------------------------------------------------------------

    @Test
    @DisplayName("ordinary stock-tracked item appears in purchase search with every field PurchaseFormPage consumes")
    void ordinaryItemIsIncludedWithConsumedFields() throws Exception {
        Shop shop = shop("pur-ordinary");
        Item item = createNormalItem(shop, "Qa name match", "QA-1", "Alt QA");
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);

        JsonNode result = pick(purchaseSearch(shop, "Qa", shop.branch().getId()), item.getId());

        // Fields read by PurchaseFormPage after selecting a search result
        // (dropdown row, summary card, per-branch input defaults, purchase
        // payload). See the field audit in the final report.
        assertThat(result.path("id").asLong()).isEqualTo(item.getId());
        assertThat(result.path("name").asText()).isEqualTo("Qa name match");
        assertThat(result.path("altName").asText()).isEqualTo("Alt QA");
        assertThat(result.path("barcode").asText()).isEqualTo("QA-1");
        assertThat(result.path("itemType").asText()).isEqualTo("NORMAL");
        assertThat(result.path("costPrice").decimalValue()).isEqualByComparingTo("20");
        assertThat(result.path("sellingPrice").decimalValue()).isEqualByComparingTo("30");
        assertThat(result.path("defaultUnit").asText()).isEqualTo("PCS");
        assertThat(result.path("availableQty").decimalValue()).isEqualByComparingTo("10");
        assertThat(result.path("availableBaseQty").asInt()).isEqualTo(10000);
        assertThat(result.path("hasNegativeStock").asBoolean()).isFalse();

        // Fields the purchase UI does not read today are still returned —
        // the response must not be narrowed when the new API is introduced.
        assertThat(result.has("batches")).isTrue();
        // Note: the kotEnabled field serializes as "isKotEnabled" (@JsonProperty).
        assertThat(result.has("isKotEnabled")).isTrue();
        assertThat(result.has("posVisible")).isTrue();
        assertThat(result.has("active")).isTrue();
        assertThat(result.has("reorderLevel")).isTrue();
        assertThat(result.has("branchIds")).isTrue();
        assertThat(result.has("ingredients")).isTrue();
        assertThat(result.has("processingOutputs")).isTrue();
    }

    // ---------------------------------------------------------------
    // B. Inactive item
    // ---------------------------------------------------------------

    @Test
    @DisplayName("inactive items are excluded from purchase search")
    void inactiveItemIsExcluded() throws Exception {
        Shop shop = shop("pur-inactive");
        Item active = createNormalItem(shop, "Qb active item", "QB-1", null);
        Item inactive = createItemJson(shop, """
                {
                  "barcode": "QB-2",
                  "name": "Qb inactive item",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0,
                  "active": false
                }
                """.formatted(shop.subCategory().getId()));

        JsonNode results = purchaseSearch(shop, "Qb", shop.branch().getId());
        assertThat(containsItem(results, active.getId())).isTrue();
        assertThat(containsItem(results, inactive.getId())).isFalse();
    }

    // ---------------------------------------------------------------
    // C. Non-stock-tracked item types
    // ---------------------------------------------------------------

    @Test
    @DisplayName("SERVICE and RECIPE items are excluded while WEIGHT items are included with converted quantities")
    void serviceAndRecipeAreExcludedButMeasuredItemsIncluded() throws Exception {
        Shop shop = shop("pur-types");
        Item normal = createNormalItem(shop, "Qc normal item", "QC-1", null);
        Item weight = createWeightItem(shop, "Qc weight item", "QC-2");
        Item service = createServiceItem(shop, "Qc service item", "QC-3");
        Item recipe = createRecipeItem(shop, "Qc recipe item", "QC-4");
        insertBatch(shop.branch(), weight, 2500, StockBatchSourceType.PURCHASE);

        JsonNode results = purchaseSearch(shop, "Qc", shop.branch().getId());
        assertThat(containsItem(results, normal.getId())).isTrue();
        assertThat(containsItem(results, service.getId())).isFalse();
        assertThat(containsItem(results, recipe.getId())).isFalse();

        // WEIGHT is stock-tracked: base grams, display kilograms.
        JsonNode weightResult = pick(results, weight.getId());
        assertThat(weightResult.path("itemType").asText()).isEqualTo("WEIGHT");
        assertThat(weightResult.path("availableBaseQty").asInt()).isEqualTo(2500);
        assertThat(weightResult.path("availableQty").decimalValue()).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("WEIGHT items disappear from purchase search when weight items are disabled in app configuration")
    void weightItemRespectsWeightItemsConfiguration() throws Exception {
        Shop shop = shop("pur-weight-cfg");
        Item weight = createWeightItem(shop, "Qd weight item", "QD-1");
        insertBatch(shop.branch(), weight, 1000, StockBatchSourceType.PURCHASE);

        assertThat(containsItem(purchaseSearch(shop, "Qd", shop.branch().getId()), weight.getId())).isTrue();

        putJson("/app-configuration", shop.tenantId(), shop.token(), """
                {
                  "recipeItemsEnabled": true,
                  "weightItemsEnabled": false,
                  "servicesEnabled": true,
                  "tableManagementEnabled": true,
                  "dineInEnabled": true,
                  "categoryMode": "MAIN_AND_SUB",
                  "stockOverrideMode": "MANAGER_OVERRIDE"
                }
                """);

        assertThat(containsItem(purchaseSearch(shop, "Qd", shop.branch().getId()), weight.getId())).isFalse();
    }

    // ---------------------------------------------------------------
    // D. Deleted items
    // ---------------------------------------------------------------

    @Test
    @DisplayName("hard-deleted items vanish and soft-deleted rows are hidden from purchase search")
    void deletedItemsAreExcluded() throws Exception {
        Shop shop = shop("pur-deleted");
        Item hardDeleted = createNormalItem(shop, "Qe hard delete", "QE-1", null);
        Item softDeleted = createNormalItem(shop, "Qe soft delete", "QE-2", null);
        Item kept = createNormalItem(shop, "Qe kept item", "QE-3", null);

        // The delete API hard-deletes rows without history or references.
        deleteExpectingStatus("/items/" + hardDeleted.getId(), shop.tenantId(), shop.token(), 200);

        // Rows with deleted_at set are hidden by Item's @SQLRestriction, the
        // soft-delete convention the entity documents (MISS-06).
        jdbcTemplate.update("UPDATE items SET deleted_at = NOW() WHERE id = ?", softDeleted.getId());

        JsonNode results = purchaseSearch(shop, "Qe", shop.branch().getId());
        assertThat(containsItem(results, hardDeleted.getId())).isFalse();
        assertThat(containsItem(results, softDeleted.getId())).isFalse();
        assertThat(containsItem(results, kept.getId())).isTrue();
    }

    // ---------------------------------------------------------------
    // E. Search term behavior
    // ---------------------------------------------------------------

    @Test
    @DisplayName("search matches name, alternate name and barcode, case-insensitively")
    void searchMatchesNameAltNameAndBarcodeCaseInsensitively() throws Exception {
        Shop shop = shop("pur-terms");
        Item byName = createNormalItem(shop, "Qf name match", "XF-1", "xf one");
        Item byAltName = createNormalItem(shop, "Xf plain name", "XF-2", "Qf alt match");
        Item byBarcode = createNormalItem(shop, "Xf other name", "QF-3", null);

        // Name substring.
        assertThat(containsItem(purchaseSearch(shop, "Qf", shop.branch().getId()), byName.getId())).isTrue();
        // Alternate-name substring (the LIKE path matches altName).
        assertThat(containsItem(purchaseSearch(shop, "Qf", shop.branch().getId()), byAltName.getId())).isTrue();
        // Barcode substring.
        assertThat(containsItem(purchaseSearch(shop, "Qf", shop.branch().getId()), byBarcode.getId())).isTrue();
        // Case-insensitive on both sides of the comparison.
        assertThat(containsItem(purchaseSearch(shop, "qf", shop.branch().getId()), byName.getId())).isTrue();
        assertThat(containsItem(purchaseSearch(shop, "QF", shop.branch().getId()), byName.getId())).isTrue();
    }

    @Test
    @DisplayName("one-character and empty search terms are accepted and return every eligible item")
    void shortAndEmptySearchTermsAreAccepted() throws Exception {
        Shop shop = shop("pur-short");
        Item first = createNormalItem(shop, "Qg first item", "QG-1", null);
        Item second = createNormalItem(shop, "Qg second item", "QG-2", null);
        Item excluded = createServiceItem(shop, "Qg service item", "QG-3");

        // The backend has no minimum query length; the >= 3 threshold only
        // switches between the FULLTEXT and LIKE queries. One character works.
        JsonNode oneChar = purchaseSearch(shop, "Q", shop.branch().getId());
        assertThat(containsItem(oneChar, first.getId())).isTrue();

        // Empty search matches every row via LIKE '%%', then applies the same
        // eligibility filters: only the stock-tracked active items survive.
        JsonNode empty = purchaseSearch(shop, "", shop.branch().getId());
        assertThat(containsItem(empty, first.getId())).isTrue();
        assertThat(containsItem(empty, second.getId())).isTrue();
        assertThat(containsItem(empty, excluded.getId())).isFalse();
    }

    // ---------------------------------------------------------------
    // 4. Role-based branch scope (resolvePurchaseSearchBranchId)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("manager searches are pinned to the assigned branch; the branchId parameter cannot override it")
    void managerSearchIsPinnedToAssignedBranch() throws Exception {
        Shop shop = shop("pur-manager");
        Item item = createNormalItem(shop, "Qh scope item", "QH-1", null);
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        Branch other = createBranch("MOTH-" + uniqueKey("B"));
        insertBatch(other, item, 3000, StockBatchSourceType.PURCHASE);
        insertBatch(other, item, -1000, StockBatchSourceType.OVERRIDE);

        String managerToken = login(shop.tenantId(), shop.fixture().manager().getUsername(), DEFAULT_PASSWORD);

        // Requesting another branch (or 0) is ignored: the manager's assigned
        // branch is used instead.
        for (String requested : new String[] {String.valueOf(other.getId()), "0", ""}) {
            JsonNode result = pick(
                    purchaseSearchAs(shop, "Qh", requested, managerToken), item.getId());
            assertThat(result.path("availableBaseQty").asInt()).isEqualTo(10000);
            assertThat(result.path("hasNegativeStock").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("cashier searches are pinned to the assigned branch exactly like managers")
    void cashierSearchIsPinnedToAssignedBranch() throws Exception {
        Shop shop = shop("pur-cashier");
        Item item = createNormalItem(shop, "Qi scope item", "QI-1", null);
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        Branch other = createBranch("COTH-" + uniqueKey("B"));
        insertBatch(other, item, 3000, StockBatchSourceType.PURCHASE);

        String cashierToken = login(shop.tenantId(), shop.fixture().cashier().getUsername(), DEFAULT_PASSWORD);

        JsonNode result = pick(
                purchaseSearchAs(shop, "Qi", String.valueOf(other.getId()), cashierToken), item.getId());
        assertThat(result.path("availableBaseQty").asInt()).isEqualTo(10000);
    }

    @Test
    @DisplayName("manager or cashier without an assigned branch is rejected with 400")
    void managerWithoutAssignedBranchIsRejected() throws Exception {
        Shop shop = shop("pur-no-branch");
        createNormalItem(shop, "Qj any item", "QJ-1", null);
        User unassignedManager = createUser(
                shop.tenantId(), uniqueKey("mgr"), Role.MANAGER, null, DEFAULT_PASSWORD, true);
        String token = login(shop.tenantId(), unassignedManager.getUsername(), DEFAULT_PASSWORD);

        JsonNode problem = jsonRequest(
                HttpMethod.GET, "/items/searchForPurchase?name=Qj", shop.tenantId(), token, null, 400);
        assertThat(problem.path("message").asText()).contains("User branch not assigned");
    }

    @Test
    @DisplayName("admin branchId selects the scope: concrete branch, 0 = all branches, omitted = no stock block")
    void adminBranchParameterSelectsScope() throws Exception {
        Shop shop = shop("pur-admin");
        Item item = createNormalItem(shop, "Qk scope item", "QK-1", null);
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        Branch other = createBranch("AOTH-" + uniqueKey("B"));
        long otherPositive = insertBatch(other, item, 3000, StockBatchSourceType.PURCHASE);
        long otherNegative = insertBatch(other, item, -1000, StockBatchSourceType.OVERRIDE);

        // Concrete branch: only that branch's rows.
        JsonNode concrete = pick(purchaseSearch(shop, "Qk", other.getId()), item.getId());
        assertThat(concrete.path("availableBaseQty").asInt()).isEqualTo(2000);
        assertThat(concrete.path("hasNegativeStock").asBoolean()).isTrue();

        // branchId=0 aggregates every branch: net 10,000 + 3,000 - 1,000.
        JsonNode all = pick(purchaseSearch(shop, "Qk", 0L), item.getId());
        assertThat(all.path("availableBaseQty").asInt()).isEqualTo(12000);
        assertThat(all.path("availableQty").decimalValue()).isEqualByComparingTo("12");
        assertThat(all.path("hasNegativeStock").asBoolean()).isTrue();
        List<Long> batchIds = new ArrayList<>();
        for (JsonNode batch : all.path("batches")) {
            batchIds.add(batch.path("batchId").asLong());
        }
        assertThat(batchIds).contains(otherPositive).doesNotContain(otherNegative);

        // Omitted branchId: the stock block never runs — null quantities and
        // no negative flag, even though stock exists.
        JsonNode unscoped = pick(purchaseSearch(shop, "Qk", null), item.getId());
        assertThat(unscoped.path("availableBaseQty").isNull()).isTrue();
        assertThat(unscoped.path("availableQty").isNull()).isTrue();
        assertThat(unscoped.path("batches")).isEmpty();
        assertThat(unscoped.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // 5. Stock aggregation behavior
    // ---------------------------------------------------------------

    @Test
    @DisplayName("zero-only, negative-only and never-stocked items all stay eligible with their exact aggregates")
    void stockAggregationForEdgeLedgers() throws Exception {
        Shop shop = shop("pur-aggregation");
        Item zeroOnly = createNormalItem(shop, "Ql zero item", "QL-1", null);
        insertBatch(shop.branch(), zeroOnly, 0, StockBatchSourceType.PURCHASE);
        Item negativeOnly = createNormalItem(shop, "Ql negative item", "QL-2", null);
        insertBatch(shop.branch(), negativeOnly, -3000, StockBatchSourceType.OVERRIDE);
        Item neverStocked = createNormalItem(shop, "Ql never stocked", "QL-3", null);

        // Unlike POS searchForPos, purchase search has no never-stocked
        // exclusion: every stock-tracked active item is eligible.
        JsonNode zeroResult = pick(purchaseSearch(shop, "Ql", shop.branch().getId()), zeroOnly.getId());
        assertThat(zeroResult.path("availableBaseQty").asInt()).isZero();
        assertThat(zeroResult.path("availableQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(zeroResult.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(zeroResult.path("batches")).isEmpty();

        JsonNode negativeResult = pick(purchaseSearch(shop, "Ql", shop.branch().getId()), negativeOnly.getId());
        assertThat(negativeResult.path("availableBaseQty").asInt()).isEqualTo(-3000);
        assertThat(negativeResult.path("availableQty").decimalValue()).isEqualByComparingTo("-3");
        assertThat(negativeResult.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(negativeResult.path("batches")).isEmpty();

        JsonNode neverResult = pick(purchaseSearch(shop, "Ql", shop.branch().getId()), neverStocked.getId());
        assertThat(neverResult.path("availableBaseQty").asInt()).isZero();
        assertThat(neverResult.path("availableQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(neverResult.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(neverResult.path("batches")).isEmpty();
    }

    @Test
    @DisplayName("mixed positive/negative ledger nets every row, flags the negative and lists only positive batches")
    void mixedLedgerNetFlagAndBatches() throws Exception {
        Shop shop = shop("pur-mixed");
        Item item = createNormalItem(shop, "Qm mixed item", "QM-1", null);
        long positive = insertBatch(shop.branch(), item, 5000, StockBatchSourceType.PURCHASE);
        insertBatch(shop.branch(), item, -2000, StockBatchSourceType.OVERRIDE);

        JsonNode result = pick(purchaseSearch(shop, "Qm", shop.branch().getId()), item.getId());
        assertThat(result.path("availableBaseQty").asInt()).isEqualTo(3000);
        assertThat(result.path("availableQty").decimalValue()).isEqualByComparingTo("3");
        assertThat(result.path("hasNegativeStock").asBoolean()).isTrue();
        List<Long> batchIds = new ArrayList<>();
        for (JsonNode batch : result.path("batches")) {
            batchIds.add(batch.path("batchId").asLong());
        }
        assertThat(batchIds).containsExactly(positive);
    }

    // ---------------------------------------------------------------
    // 6. Negative-stock prompt compatibility
    // ---------------------------------------------------------------

    @Test
    @DisplayName("response fields satisfy the PurchaseFormPage prompt condition hasNegativeStock || availableQty < 0")
    void negativeStockPromptFieldsMatchFrontendCondition() throws Exception {
        Shop shop = shop("pur-prompt");
        // Net-positive with a negative row: the flag alone must trigger the
        // prompt (hasNegativeStock=true while availableQty=3 > 0).
        Item flagOnly = createNormalItem(shop, "Qn flag item", "QN-1", null);
        insertBatch(shop.branch(), flagOnly, 5000, StockBatchSourceType.PURCHASE);
        insertBatch(shop.branch(), flagOnly, -2000, StockBatchSourceType.OVERRIDE);
        // Negative net: both parts of the OR are true.
        Item negativeNet = createNormalItem(shop, "Qn negative item", "QN-2", null);
        insertBatch(shop.branch(), negativeNet, -3000, StockBatchSourceType.OVERRIDE);
        // Healthy stock: neither part is true.
        Item healthy = createNormalItem(shop, "Qn healthy item", "QN-3", null);
        insertBatch(shop.branch(), healthy, 4000, StockBatchSourceType.PURCHASE);

        JsonNode flagResult = pick(purchaseSearch(shop, "Qn", shop.branch().getId()), flagOnly.getId());
        assertThat(flagResult.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(flagResult.path("availableQty").decimalValue()).isEqualByComparingTo("3");

        JsonNode negativeResult = pick(purchaseSearch(shop, "Qn", shop.branch().getId()), negativeNet.getId());
        assertThat(negativeResult.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(negativeResult.path("availableQty").decimalValue()).isEqualByComparingTo("-3");

        JsonNode healthyResult = pick(purchaseSearch(shop, "Qn", shop.branch().getId()), healthy.getId());
        assertThat(healthyResult.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(healthyResult.path("availableQty").decimalValue()).isEqualByComparingTo("4");
    }

    // ---------------------------------------------------------------
    // 8. Barcode fallback (GET /items/barcode/{barcode})
    // ---------------------------------------------------------------

    @Test
    @DisplayName("exact barcode lookup returns the item with stock fields and the consumed response fields")
    void barcodeLookupReturnsItemWithConsumedFields() throws Exception {
        Shop shop = shop("pur-barcode");
        Item item = createNormalItem(shop, "Qo barcode item", "QO-777", "Alt QO");
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);

        JsonNode result = getJson(
                "/items/barcode/QO-777?branchId=" + shop.branch().getId(),
                shop.tenantId(), shop.token());

        assertThat(result.path("id").asLong()).isEqualTo(item.getId());
        assertThat(result.path("name").asText()).isEqualTo("Qo barcode item");
        assertThat(result.path("altName").asText()).isEqualTo("Alt QO");
        assertThat(result.path("barcode").asText()).isEqualTo("QO-777");
        assertThat(result.path("itemType").asText()).isEqualTo("NORMAL");
        assertThat(result.path("costPrice").decimalValue()).isEqualByComparingTo("20");
        assertThat(result.path("sellingPrice").decimalValue()).isEqualByComparingTo("30");
        assertThat(result.path("defaultUnit").asText()).isEqualTo("PCS");
        assertThat(result.path("availableBaseQty").asInt()).isEqualTo(10000);
        assertThat(result.path("availableQty").decimalValue()).isEqualByComparingTo("10");
        assertThat(result.path("hasNegativeStock").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("barcode lookup: unknown barcode 404s, inactive/hidden items 404, non-stock items pass")
    void barcodeLookupRejectionsAndNonStockItems() throws Exception {
        Shop shop = shop("pur-barcode-2");
        Item inactive = createItemJson(shop, """
                {
                  "barcode": "QP-1",
                  "name": "Qp inactive item",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0,
                  "active": false
                }
                """.formatted(shop.subCategory().getId()));
        Item hidden = createItemJson(shop, """
                {
                  "barcode": "QP-2",
                  "name": "Qp hidden item",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0,
                  "posVisible": false
                }
                """.formatted(shop.subCategory().getId()));
        Item recipe = createRecipeItem(shop, "Qp recipe item", "QP-3");

        // Unknown barcode: with scale decoding off (the default) this is a
        // plain miss, tagged E10 for the till.
        JsonNode miss = jsonRequest(
                HttpMethod.GET, "/items/barcode/NOPE-404?branchId=" + shop.branch().getId(),
                shop.tenantId(), shop.token(), null, 404);
        assertThat(miss.path("message").asText()).contains("Item not found (E10)");

        // Inactive items are rejected...
        JsonNode inactiveProblem = jsonRequest(
                HttpMethod.GET, "/items/barcode/QP-1?branchId=" + shop.branch().getId(),
                shop.tenantId(), shop.token(), null, 404);
        assertThat(inactiveProblem.path("message").asText()).contains("Item not available in POS (E12)");

        // ...including items hidden from the POS: purchase SEARCH still lists
        // posVisible=false items, but the barcode endpoint rejects them.
        JsonNode hiddenProblem = jsonRequest(
                HttpMethod.GET, "/items/barcode/QP-2?branchId=" + shop.branch().getId(),
                shop.tenantId(), shop.token(), null, 404);
        assertThat(hiddenProblem.path("message").asText()).contains("Item not available in POS (E12)");
        assertThat(containsItem(purchaseSearch(shop, "Qp", shop.branch().getId()), hidden.getId())).isTrue();

        // Non-stock items ARE returned by the barcode endpoint (unlike
        // purchase search): a RECIPE comes back with no stock fields.
        JsonNode recipeResult = getJson(
                "/items/barcode/QP-3?branchId=" + shop.branch().getId(),
                shop.tenantId(), shop.token());
        assertThat(recipeResult.path("id").asLong()).isEqualTo(recipe.getId());
        assertThat(recipeResult.path("availableBaseQty").isNull()).isTrue();
        assertThat(recipeResult.path("batches")).isEmpty();
    }

    @Test
    @DisplayName("barcode lookup without a branchId returns the item with null stock fields")
    void barcodeLookupWithoutBranchIdSkipsStock() throws Exception {
        Shop shop = shop("pur-barcode-3");
        Item item = createNormalItem(shop, "Qq barcode item", "QQ-9", null);
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);

        JsonNode result = getJson("/items/barcode/QQ-9", shop.tenantId(), shop.token());
        assertThat(result.path("id").asLong()).isEqualTo(item.getId());
        assertThat(result.path("availableBaseQty").isNull()).isTrue();
        assertThat(result.path("availableQty").isNull()).isTrue();
        assertThat(result.path("batches")).isEmpty();
        assertThat(result.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // 9. Purchase workflow protection
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a purchase created from search-result fields receives stock as before")
    void searchResultFeedsPurchaseCreation() throws Exception {
        Shop shop = shop("pur-workflow");
        Item item = createNormalItem(shop, "Qr workflow item", "QR-1", null);
        insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        com.chala.posapp.entity.supplier.Supplier supplier = new com.chala.posapp.entity.supplier.Supplier();
        supplier.setName(uniqueKey("PUR supplier"));
        supplier.setPhone("07" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L)));
        supplier.setAddress("Supplier address");
        supplier.setActive(true);
        supplier = supplierRepository.save(supplier);

        // Exactly the fields PurchaseFormPage reads from the search result and
        // sends on: item id plus the cost/sell defaults it seeds the rows with.
        JsonNode searchResult = pick(purchaseSearch(shop, "Qr", shop.branch().getId()), item.getId());
        long itemId = searchResult.path("id").asLong();
        String costPrice = searchResult.path("costPrice").decimalValue().toPlainString();
        String sellingPrice = searchResult.path("sellingPrice").decimalValue().toPlainString();

        JsonNode purchase = postJson("/purchases", shop.tenantId(), shop.token(), """
                {
                  "supplierId": %d,
                  "invoiceNo": "%s",
                  "branches": [{
                    "branchId": %d,
                    "items": [{
                      "itemId": %d,
                      "qty": 2,
                      "costPrice": %s,
                      "sellingPrice": %s,
                      "zeroNegativeStock": false
                    }]
                  }]
                }
                """.formatted(supplier.getId(), uniqueKey("INV"), shop.branch().getId(),
                itemId, costPrice, sellingPrice));

        assertThat(purchase.path("purchaseId").asLong()).isPositive();
        // 10,000 base + 2 display units (2,000 base) received.
        assertThat(stockBatchRepository.getTotalQuantityByItemAndBranch(
                shop.branch().getId(), item.getId())).isEqualTo(12000);
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
        category.setName(uniqueKey("Pur category"));
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(uniqueKey("Pur stock"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        return new Shop(fixture, token, subCategory);
    }

    private Item createNormalItem(Shop shop, String name, String barcode, String altName) throws Exception {
        return createItemJson(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "altName": %s,
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0
                }
                """.formatted(barcode, name,
                altName == null ? "null" : "\"" + altName + "\"",
                shop.subCategory().getId()));
    }

    private Item createWeightItem(Shop shop, String name, String barcode) throws Exception {
        return createItemJson(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 1000,
                  "sellingPrice": 1400,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(barcode, name, shop.subCategory().getId()));
    }

    private Item createServiceItem(Shop shop, String name, String barcode) throws Exception {
        return createItemJson(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 100,
                  "reorderLevel": 0,
                  "itemType": "SERVICE",
                  "branchIds": [%d]
                }
                """.formatted(barcode, name, shop.subCategory().getId(), shop.branch().getId()));
    }

    private Item createRecipeItem(Shop shop, String name, String barcode) throws Exception {
        return createItemJson(shop, """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 0,
                  "sellingPrice": 180,
                  "reorderLevel": 0,
                  "itemType": "RECIPE",
                  "ingredients": []
                }
                """.formatted(barcode, name, shop.subCategory().getId()));
    }

    private Item createItemJson(Shop shop, String jsonBody) throws Exception {
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

    /** Seeds one batch row in base units; the id follows insert order. */
    private long insertBatch(Branch branch, Item item, int baseQuantity, StockBatchSourceType sourceType) {
        return stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(baseQuantity)
                .originalQuantity(Math.abs(baseQuantity))
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .sourceType(sourceType)
                .batchCode(uniqueKey("SEED"))
                .build()).getId();
    }

    /** branchId=null omits the query parameter. The empty name is sent as an
     *  empty value, matching a cleared search box. */
    private JsonNode purchaseSearch(Shop shop, String name, Long branchId) throws Exception {
        String url = "/items/searchForPurchase?name=" + name
                + (branchId != null ? "&branchId=" + branchId : "");
        return getJson(url, shop.tenantId(), shop.token());
    }

    /** Same endpoint with an explicit branchId query value ("" = parameter
     *  present but empty) and a chosen caller, for role-scope tests. */
    private JsonNode purchaseSearchAs(Shop shop, String name, String branchIdParam, String token) throws Exception {
        String url = "/items/searchForPurchase?name=" + name
                + (branchIdParam.isEmpty() ? "" : "&branchId=" + branchIdParam);
        return getJson(url, shop.tenantId(), token);
    }

    private JsonNode pick(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return candidate;
            }
        }
        throw new AssertionError("item " + itemId + " missing from purchase search results");
    }

    private boolean containsItem(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return true;
            }
        }
        return false;
    }
}
