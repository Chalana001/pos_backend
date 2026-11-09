package com.chala.posapp.integration;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.SubCategory;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the stock semantics of {@code ItemService.searchForPos} (the
 * {@code GET /items/searchForPos} endpoint) exactly as they exist today:
 * stock eligibility, net quantity, the negative-stock flag, and the POS batch
 * list and its ordering. These tests are a regression net for the planned
 * change that replaces the per-item repository calls with bulk queries — the
 * observable response must stay identical.
 *
 * <p>Search terms are deliberately two characters: terms of three characters
 * or more run through the MySQL FULLTEXT index ({@code searchForPosFt}), which
 * H2 cannot execute (see {@code FoodRecipeDineInIntegrationTest}). The
 * two-character LIKE fallback reaches the same stock code paths, which is what
 * is under test here.
 *
 * <p>Quantities are asserted in base units: a NORMAL item is stored in
 * thousandths of a piece (10 display = 10,000 base), a WEIGHT item in grams —
 * see {@code QuantityConversionUtil}.
 */
class SearchForPosStockSemanticsIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // A. Never-stocked ordinary item
    // ---------------------------------------------------------------

    @Test
    @DisplayName("never-stocked ordinary item is excluded when a branch scope is given, included when branchId is omitted")
    void neverStockedItemIsExcludedForABranchButIncludedWithoutOne() throws Exception {
        Shop shop = shop("never-stocked");
        Item item = createItem(shop, "Qa never stocked item");
        // Precondition: the item has never had any batch row at all.
        assertThat(stockBatchRepository.findByItemId(item.getId())).isEmpty();

        assertThat(containsItem(posSearch(shop, "Qa", shop.branch().getId()), item.getId())).isFalse();
        assertThat(containsItem(posSearch(shop, "Qa", 0L), item.getId())).isFalse();

        // With no branch scope the stock block is skipped entirely, so the
        // same item is returned with no stock information.
        JsonNode unscoped = pick(posSearch(shop, "Qa", null), item.getId());
        assertThat(unscoped.path("availableBaseQty").isNull()).isTrue();
        assertThat(unscoped.path("availableQty").isNull()).isTrue();
        assertThat(unscoped.path("batches")).isEmpty();
        assertThat(unscoped.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // B. Zero-only batch history
    // ---------------------------------------------------------------

    @Test
    @DisplayName("zero-only batch history stays eligible with zero quantity and an empty POS batch list")
    void zeroOnlyBatchHistoryIsEligibleWithZeroQuantity() throws Exception {
        Shop shop = shop("zero-only");
        Item item = createItem(shop, "Qb zero history item");
        insertBatch(shop.branch(), item, 0, StockBatchSourceType.PURCHASE);

        JsonNode posItem = pick(posSearch(shop, "Qb", shop.branch().getId()), item.getId());
        assertThat(posItem.path("availableBaseQty").asInt()).isZero();
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(posItem.path("batches")).isEmpty();
    }

    // ---------------------------------------------------------------
    // C. Negative-only batch history
    // ---------------------------------------------------------------

    @Test
    @DisplayName("negative-only batch history stays eligible with the negative net, flag set, and no POS batches")
    void negativeOnlyBatchHistoryIsEligibleWithNegativeNet() throws Exception {
        Shop shop = shop("negative-only");
        Item item = createItem(shop, "Qc negative history item");
        insertBatch(shop.branch(), item, -3000, StockBatchSourceType.OVERRIDE);

        JsonNode posItem = pick(posSearch(shop, "Qc", shop.branch().getId()), item.getId());
        assertThat(posItem.path("availableBaseQty").asInt()).isEqualTo(-3000);
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("-3");
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(posItem.path("batches")).isEmpty();
    }

    // ---------------------------------------------------------------
    // D. Positive stock
    // ---------------------------------------------------------------

    @Test
    @DisplayName("positive stock reports the summed net, no negative flag, and the positive batches")
    void positiveStockReportsNetQuantityFlagAndBatches() throws Exception {
        Shop shop = shop("positive");
        Item item = createItem(shop, "Qd positive stock item");
        long first = insertBatch(shop.branch(), item, 2000, StockBatchSourceType.PURCHASE);
        long second = insertBatch(shop.branch(), item, 5000, StockBatchSourceType.PURCHASE);

        JsonNode posItem = pick(posSearch(shop, "Qd", shop.branch().getId()), item.getId());
        assertThat(posItem.path("availableBaseQty").asInt()).isEqualTo(7000);
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("7");
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(posBatchIds(posItem)).containsExactlyInAnyOrder(first, second);
    }

    // ---------------------------------------------------------------
    // E. Mixed positive / negative / zero stock
    // ---------------------------------------------------------------

    @Test
    @DisplayName("mixed +10 / -3 / 0 batches net all rows, flag the negative, and list only the positive batch")
    void mixedLedgerNetsAllRowsAndListsOnlyPositiveBatches() throws Exception {
        Shop shop = shop("mixed");
        Item item = createItem(shop, "Qe mixed stock item");
        // Display units for a NORMAL item: +10 = 10,000 base, -3 = -3,000 base.
        long positive = insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        insertBatch(shop.branch(), item, -3000, StockBatchSourceType.OVERRIDE);
        insertBatch(shop.branch(), item, 0, StockBatchSourceType.PURCHASE);

        JsonNode posItem = pick(posSearch(shop, "Qe", shop.branch().getId()), item.getId());
        // Net includes every applicable row: 10,000 - 3,000 + 0 = 7,000 base.
        assertThat(posItem.path("availableBaseQty").asInt()).isEqualTo(7000);
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("7");
        assertThat(posItem.path("hasNegativeStock").asBoolean()).isTrue();

        // POS batches contain only the positive-quantity row.
        assertThat(posBatchIds(posItem)).containsExactly(positive);
        JsonNode batch = posItem.path("batches").get(0);
        assertThat(batch.path("qty").asInt()).isEqualTo(10000);
        assertThat(batch.path("displayQty").decimalValue()).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------
    // F. Branch isolation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a branch-scoped POS search returns only that branch's batches and net quantity")
    void branchScopedPosSearchReturnsOnlyThatBranchsStock() throws Exception {
        Shop shop = shop("branch-scope");
        Branch other = createBranch("FOTH-" + uniqueKey("B"));
        Item item = createItem(shop, "Qf branch scoped item");
        long mainPositive = insertBatch(shop.branch(), item, 5000, StockBatchSourceType.PURCHASE);
        long otherPositive = insertBatch(other, item, 3000, StockBatchSourceType.PURCHASE);
        long otherNegative = insertBatch(other, item, -1000, StockBatchSourceType.OVERRIDE);

        JsonNode mainView = pick(posSearch(shop, "Qf", shop.branch().getId()), item.getId());
        assertThat(mainView.path("availableBaseQty").asInt()).isEqualTo(5000);
        assertThat(mainView.path("availableQty").decimalValue()).isEqualByComparingTo("5");
        assertThat(mainView.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(posBatchIds(mainView)).containsExactly(mainPositive);

        JsonNode otherView = pick(posSearch(shop, "Qf", other.getId()), item.getId());
        assertThat(otherView.path("availableBaseQty").asInt()).isEqualTo(2000);
        assertThat(otherView.path("availableQty").decimalValue()).isEqualByComparingTo("2");
        assertThat(otherView.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(posBatchIds(otherView)).containsExactly(otherPositive);

        // An item stocked only at the other branch is not returned for this
        // branch at all: nothing was ever stocked here.
        Item otherOnly = createItem(shop, "Qx other branch only item");
        long otherOnlyBatch = insertBatch(other, otherOnly, 4000, StockBatchSourceType.PURCHASE);
        assertThat(otherOnlyBatch).isPositive();
        assertThat(containsItem(posSearch(shop, "Qx", shop.branch().getId()), otherOnly.getId())).isFalse();
        assertThat(containsItem(posSearch(shop, "Qx", other.getId()), otherOnly.getId())).isTrue();
    }

    // ---------------------------------------------------------------
    // G. branchId = 0 (all-branch scope)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branchId=0 aggregates every branch and is not equivalent to the null scope")
    void zeroBranchIdAggregatesAllBranches() throws Exception {
        Shop shop = shop("all-branches");
        Branch other = createBranch("GOTH-" + uniqueKey("B"));
        Item item = createItem(shop, "Qg all branch item");
        long mainPositive = insertBatch(shop.branch(), item, 10000, StockBatchSourceType.PURCHASE);
        long otherPositive = insertBatch(other, item, 7000, StockBatchSourceType.PURCHASE);
        insertBatch(other, item, -2000, StockBatchSourceType.OVERRIDE);

        // Net quantity sums every branch's rows; the POS batch list holds the
        // positive batches of every branch.
        JsonNode all = pick(posSearch(shop, "Qg", 0L), item.getId());
        assertThat(all.path("availableBaseQty").asInt()).isEqualTo(15000);
        assertThat(all.path("availableQty").decimalValue()).isEqualByComparingTo("15");
        assertThat(all.path("hasNegativeStock").asBoolean()).isTrue();
        assertThat(posBatchIds(all)).containsExactlyInAnyOrder(mainPositive, otherPositive);

        // Historical batch eligibility is evaluated across all branches too:
        // an item stocked only at the other branch is found under branchId=0.
        Item otherOnly = createItem(shop, "Qy other stock item");
        insertBatch(other, otherOnly, 4000, StockBatchSourceType.PURCHASE);
        assertThat(containsItem(posSearch(shop, "Qy", 0L), otherOnly.getId())).isTrue();

        // branchId=0 is not the null scope: without a branch the stock block is
        // skipped and quantities come back null instead of aggregated.
        JsonNode unscoped = pick(posSearch(shop, "Qg", null), item.getId());
        assertThat(unscoped.path("availableBaseQty").isNull()).isTrue();
        assertThat(unscoped.path("availableQty").isNull()).isTrue();
        assertThat(unscoped.path("hasNegativeStock").asBoolean()).isFalse();
        assertThat(unscoped.path("batches")).isEmpty();
    }

    // ---------------------------------------------------------------
    // H. branchId = null
    // ---------------------------------------------------------------

    @Test
    @DisplayName("branchId=null skips the stock checks and represents quantity fields as null")
    void nullBranchIdSkipsStockChecksAndNullsOutQuantities() throws Exception {
        Shop shop = shop("null-branch");
        Item stocked = createItem(shop, "Qh stocked item");
        insertBatch(shop.branch(), stocked, 5000, StockBatchSourceType.PURCHASE);
        insertBatch(shop.branch(), stocked, -1000, StockBatchSourceType.OVERRIDE);
        Item neverStocked = createItem(shop, "Qz never stocked item");

        // Even with a mixed ledger the flag stays false and quantities null:
        // without a branch the stock queries are never run.
        JsonNode stockedView = pick(posSearch(shop, "Qh", null), stocked.getId());
        assertThat(stockedView.path("availableBaseQty").isNull()).isTrue();
        assertThat(stockedView.path("availableQty").isNull()).isTrue();
        assertThat(stockedView.path("batches")).isEmpty();
        assertThat(stockedView.path("hasNegativeStock").asBoolean()).isFalse();

        // The never-stocked exclusion is skipped as well.
        JsonNode neverStockedView = pick(posSearch(shop, "Qz", null), neverStocked.getId());
        assertThat(neverStockedView.path("availableBaseQty").isNull()).isTrue();
        assertThat(neverStockedView.path("availableQty").isNull()).isTrue();
        assertThat(neverStockedView.path("batches")).isEmpty();
        assertThat(neverStockedView.path("hasNegativeStock").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------
    // I. POS positive-batch ordering
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POS batches order PROCESSING first, then received time, then id")
    void posBatchesAreOrderedProcessingFirstThenReceivedAtThenId() throws Exception {
        Shop shop = shop("ordering");
        Item item = createItem(shop, "Qi ordering item");

        // Insert order (so ascending ids): procNewest, purchaseA, procOldest,
        // purchaseB. Timestamps are written after insert because
        // @CreationTimestamp overrides a supplied value on insert (same
        // approach as NegativeStockWorkflowIntegrationTest).
        long procNewest = insertBatch(shop.branch(), item, 1000, StockBatchSourceType.PROCESSING);
        long purchaseA = insertBatch(shop.branch(), item, 1000, StockBatchSourceType.PURCHASE);
        long procOldest = insertBatch(shop.branch(), item, 1000, StockBatchSourceType.PROCESSING);
        long purchaseB = insertBatch(shop.branch(), item, 1000, StockBatchSourceType.PURCHASE);

        LocalDateTime base = LocalDateTime.now().minusDays(1);
        setReceivedAt(procNewest, base.minusHours(2));
        setReceivedAt(purchaseA, base.minusHours(4));
        setReceivedAt(procOldest, base.minusHours(3));
        setReceivedAt(purchaseB, base.minusHours(4)); // ties purchaseA -> id wins

        JsonNode posItem = pick(posSearch(shop, "Qi", shop.branch().getId()), item.getId());
        // PROCESSING batches first even though the purchases are older, then
        // received time ascending within each group, then id ascending on a
        // received-time tie.
        assertThat(posBatchIds(posItem))
                .containsExactly(procOldest, procNewest, purchaseA, purchaseB);
    }

    @Test
    @DisplayName("stock batches cannot hold a NULL received_at, so the ordering never sees one")
    void stockBatchesRejectNullReceivedAt() throws Exception {
        Shop shop = shop("null-received");
        Item item = createItem(shop, "Qn null received item");
        long batchId = insertBatch(shop.branch(), item, 1000, StockBatchSourceType.PURCHASE);

        // Through JPA, @CreationTimestamp fills the column on insert.
        StockBatch saved = stockBatchRepository.findById(batchId).orElseThrow();
        assertThat(saved.getReceivedAt()).isNotNull();

        // Through raw SQL the NOT NULL column rejects a NULL, so no row with a
        // null received_at can exist and the ORDER BY's handling of NULLs is
        // not observable behaviour. No ordering rule is asserted for it.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE stock_batches SET received_at = NULL WHERE id = ?", batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------
    // J. Zero and negative batches in returned batch lists
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POS batch details exclude zero and negative rows while the net quantity includes them")
    void posBatchDetailsArePositiveOnly() throws Exception {
        Shop shop = shop("positive-only-list");
        Item item = createItem(shop, "Qj positive list item");
        long positive = insertBatch(shop.branch(), item, 5000, StockBatchSourceType.PURCHASE);
        long zero = insertBatch(shop.branch(), item, 0, StockBatchSourceType.PURCHASE);
        long negative = insertBatch(shop.branch(), item, -2000, StockBatchSourceType.OVERRIDE);

        JsonNode posItem = pick(posSearch(shop, "Qj", shop.branch().getId()), item.getId());
        assertThat(posBatchIds(posItem)).containsExactly(positive).doesNotContain(zero, negative);
        // The net quantity is still computed over every row: 5,000 + 0 - 2,000.
        assertThat(posItem.path("availableBaseQty").asInt()).isEqualTo(3000);
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------------
    // K. Unit conversion
    // ---------------------------------------------------------------

    @Test
    @DisplayName("weight item quantities convert between base grams and display kilograms")
    void weightItemQuantitiesConvertBaseGramsToDisplayKg() throws Exception {
        Shop shop = shop("weight-unit");
        Item item = createWeightItem(shop, "Qk weight item");
        long batchId = insertBatch(shop.branch(), item, 2500, StockBatchSourceType.PURCHASE);

        JsonNode posItem = pick(posSearch(shop, "Qk", shop.branch().getId()), item.getId());
        assertThat(posItem.path("availableBaseQty").asInt()).isEqualTo(2500);
        assertThat(posItem.path("availableQty").decimalValue()).isEqualByComparingTo("2.5");

        JsonNode batch = posItem.path("batches").get(0);
        assertThat(batch.path("batchId").asLong()).isEqualTo(batchId);
        assertThat(batch.path("qty").asInt()).isEqualTo(2500);
        assertThat(batch.path("displayQty").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(batch.path("displayUnit").asText()).isEqualTo("KG");
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
        category.setName(uniqueKey("Sem category"));
        category = categoryRepository.save(category);

        SubCategory subCategory = new SubCategory();
        subCategory.setName(uniqueKey("Sem stock"));
        subCategory.setCategory(category);
        subCategory = subCategoryRepository.save(subCategory);

        return new Shop(fixture, token, subCategory);
    }

    private Item createItem(Shop shop, String name) throws Exception {
        JsonNode created = postJson(
                "/items",
                shop.tenantId(),
                shop.token(),
                """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0
                }
                """.formatted(uniqueKey("SEM"), name, shop.subCategory().getId())
        );
        return itemRepository.findById(created.path("id").asLong()).orElseThrow();
    }

    private Item createWeightItem(Shop shop, String name) throws Exception {
        JsonNode created = postJson(
                "/items",
                shop.tenantId(),
                shop.token(),
                """
                {
                  "barcode": "%s",
                  "name": "%s",
                  "subCategoryId": %d,
                  "costPrice": 20,
                  "sellingPrice": 30,
                  "reorderLevel": 0,
                  "itemType": "WEIGHT",
                  "defaultUnit": "KG"
                }
                """.formatted(uniqueKey("SEM"), name, shop.subCategory().getId())
        );
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
                .batchCode(uniqueKey("SEM"))
                .build()).getId();
    }

    /** Writes the stored received time; needed because @CreationTimestamp
     *  overrides a supplied value at insert time. */
    private void setReceivedAt(long batchId, LocalDateTime receivedAt) {
        jdbcTemplate.update("UPDATE stock_batches SET received_at = ? WHERE id = ?", receivedAt, batchId);
    }

    /** branchId=null omits the query parameter, matching an unscoped call. */
    private JsonNode posSearch(Shop shop, String name, Long branchId) throws Exception {
        String url = "/items/searchForPos?name=" + name
                + (branchId != null ? "&branchId=" + branchId : "");
        return getJson(url, shop.tenantId(), shop.token());
    }

    private JsonNode pick(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return candidate;
            }
        }
        throw new AssertionError("item " + itemId + " missing from POS search results");
    }

    private boolean containsItem(JsonNode results, long itemId) {
        for (JsonNode candidate : results) {
            if (candidate.path("id").asLong() == itemId) {
                return true;
            }
        }
        return false;
    }

    private List<Long> posBatchIds(JsonNode posItem) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode batch : posItem.path("batches")) {
            ids.add(batch.path("batchId").asLong());
        }
        return ids;
    }
}
