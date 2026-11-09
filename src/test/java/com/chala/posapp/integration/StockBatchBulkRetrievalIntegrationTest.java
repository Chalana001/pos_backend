package com.chala.posapp.integration;

import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.dto.stock.ItemStockBatch;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import com.chala.posapp.util.QuantityConversionUtil;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level verification of the bulk positive-batch retrieval
 * ({@code StockBatchRepository.findAvailableBatchesForBranch} /
 * {@code findAvailableBatchesAcrossBranches}) against the legacy per-item
 * {@code findAvailableBatchesForScope} on identical fixtures and scopes.
 *
 * <p>Rules pinned here, all verified against the legacy query and the
 * TASK-001 characterizations: only {@code quantity > 0} rows are returned
 * (zero and negative rows excluded); PROCESSING-sourced batches come first;
 * then {@code receivedAt ASC}; then {@code id ASC}; per-item order is
 * preserved exactly. The legacy row order is the contract — nothing is
 * re-sorted in Java.
 *
 * <p>{@code received_at} cannot be set on insert ({@code @CreationTimestamp}
 * overrides it), so the deterministic timestamp fixtures write it afterwards
 * over JDBC — the same technique {@code NegativeStockWorkflowIntegrationTest}
 * uses for its historical-ordering tests.
 *
 * <p>Runs on the H2 test profile; the same JPQL runs on MySQL through
 * Hibernate. MySQL EXPLAIN verification is pending a Docker-enabled
 * environment and is NOT claimed here.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class StockBatchBulkRetrievalIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // A. Positive-only retrieval
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk retrieval returns every positive batch of every requested item with its fields")
    void positiveOnlyRetrievalReturnsEveryPositiveBatch() {
        Branch main = createBranch("BRKA-" + uniqueKey("B"));
        Item first = newItem("first item");
        long firstA = insertBatch(main, first, 2000, StockBatchSourceType.PURCHASE, "20.00");
        long firstB = insertBatch(main, first, 5000, StockBatchSourceType.PURCHASE, "35.00");
        Item second = newItem("second item");
        long secondA = insertBatch(main, second, 700, StockBatchSourceType.PROCESSING, "12.50");

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(first.getId(), second.getId()), main.getId());

        assertThat(batchIds(rows, first.getId())).containsExactly(firstA, firstB);
        assertThat(batchIds(rows, second.getId())).containsExactly(secondA);

        Map<Long, ItemStockBatch> byBatch = byBatch(rows);
        // Every response-relevant raw field survives the projection.
        assertThat(byBatch.get(firstA).sellingPrice()).isEqualByComparingTo("20.00");
        assertThat(byBatch.get(firstA).quantity()).isEqualTo(2000);
        assertThat(byBatch.get(firstB).sellingPrice()).isEqualByComparingTo("35.00");
        assertThat(byBatch.get(firstB).quantity()).isEqualTo(5000);
        assertThat(byBatch.get(secondA).sellingPrice()).isEqualByComparingTo("12.50");
        assertThat(byBatch.get(secondA).quantity()).isEqualTo(700);
        assertThat(byBatch.get(secondA).sourceType()).isEqualTo(StockBatchSourceType.PROCESSING);
        for (ItemStockBatch row : rows) {
            assertThat(row.itemId()).isNotNull();
            assertThat(row.receivedAt()).isNotNull();
        }
    }

    // ---------------------------------------------------------------
    // B. Zero and negative exclusion
    // ---------------------------------------------------------------

    @Test
    @DisplayName("zero and negative rows are excluded from the batch detail while the aggregate still sees them")
    void zeroAndNegativeBatchesAreExcluded() {
        Branch main = createBranch("BRKB-" + uniqueKey("B"));
        Item item = newItem("mixed ledger");
        long positive = insertBatch(main, item, 3000, StockBatchSourceType.PURCHASE, "30.00");
        insertBatch(main, item, 0, StockBatchSourceType.PURCHASE, "30.00");
        insertBatch(main, item, -2000, StockBatchSourceType.OVERRIDE, "30.00");

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(item.getId()), main.getId());
        assertThat(batchIds(rows, item.getId())).containsExactly(positive);

        // TASK-006 aggregate is independent of the positive-only detail:
        // its batch count covers every row and the net sums every row.
        ItemStockAggregate aggregate = stockBatchRepository.aggregateStockForBranch(
                List.of(item.getId()), main.getId()).get(0);
        assertThat(aggregate.batchCount()).isEqualTo(3L);
        assertThat(aggregate.totalQuantity()).isEqualTo(1000L);
        assertThat(aggregate.negativeBatchCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // C. Processing-first ordering
    // ---------------------------------------------------------------

    @Test
    @DisplayName("PROCESSING batches appear before all other source types")
    void processingBatchesComeFirst() {
        Branch main = createBranch("BRKC-" + uniqueKey("B"));
        Item item = newItem("mixed sources");
        long purchase = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long processing = insertBatch(main, item, 1000, StockBatchSourceType.PROCESSING, "31.00");
        long override = insertBatch(main, item, 1000, StockBatchSourceType.OVERRIDE, "32.00");
        long secondProcessing = insertBatch(main, item, 1000, StockBatchSourceType.PROCESSING, "33.00");

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(item.getId()), main.getId());

        // Both PROCESSING rows first (in their own received/id order), then
        // the rest — even though the other rows were interleaved by insert.
        assertThat(batchIds(rows, item.getId()))
                .containsExactly(processing, secondProcessing, purchase, override);
    }

    // ---------------------------------------------------------------
    // D. Received-time ordering
    // ---------------------------------------------------------------

    @Test
    @DisplayName("batches inside one priority group ascend by received time")
    void receivedTimeOrdersWithinPriorityGroup() {
        Branch main = createBranch("BRKD-" + uniqueKey("B"));
        Item item = newItem("timed batches");
        long late = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long early = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long middle = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        // @CreationTimestamp overrides supplied values on insert, so the
        // historical ordering is written afterwards over JDBC.
        setReceivedAt(late, LocalDateTime.of(2026, 3, 1, 12, 0));
        setReceivedAt(early, LocalDateTime.of(2026, 1, 1, 12, 0));
        setReceivedAt(middle, LocalDateTime.of(2026, 2, 1, 12, 0));

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(item.getId()), main.getId());
        assertThat(batchIds(rows, item.getId())).containsExactly(early, middle, late);
    }

    // ---------------------------------------------------------------
    // E. ID tie-breaker
    // ---------------------------------------------------------------

    @Test
    @DisplayName("same received time and same priority falls back to ascending batch id")
    void idBreaksReceivedTimeTies() {
        Branch main = createBranch("BRKE-" + uniqueKey("B"));
        Item item = newItem("tied batches");
        long first = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long second = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long third = insertBatch(main, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        LocalDateTime tied = LocalDateTime.of(2026, 2, 1, 12, 0);
        setReceivedAt(first, tied);
        setReceivedAt(second, tied);
        setReceivedAt(third, tied);

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(item.getId()), main.getId());
        assertThat(batchIds(rows, item.getId())).containsExactly(first, second, third);
    }

    // ---------------------------------------------------------------
    // F. Concrete branch isolation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the concrete-branch query returns only the selected branch's batches")
    void concreteBranchIsolation() {
        Branch first = createBranch("BRKF1-" + uniqueKey("B"));
        Branch second = createBranch("BRKF2-" + uniqueKey("B"));
        Item shared = newItem("shared item");
        long firstBranchBatch = insertBatch(first, shared, 4000, StockBatchSourceType.PURCHASE, "30.00");
        long secondBranchBatch = insertBatch(second, shared, 6000, StockBatchSourceType.PURCHASE, "31.00");

        List<ItemStockBatch> firstScope = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(shared.getId()), first.getId());
        assertThat(batchIds(firstScope, shared.getId())).containsExactly(firstBranchBatch);

        List<ItemStockBatch> secondScope = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(shared.getId()), second.getId());
        assertThat(batchIds(secondScope, shared.getId())).containsExactly(secondBranchBatch);

        // A null branch id matches no rows — it must never become the
        // all-branch scope (ItemService skips the stock block for null).
        assertThat(stockBatchRepository.findAvailableBatchesForBranch(List.of(shared.getId()), null)).isEmpty();
    }

    // ---------------------------------------------------------------
    // G. All-branch retrieval
    // ---------------------------------------------------------------

    @Test
    @DisplayName("all-branch retrieval includes every branch and keeps the per-item ordering rules")
    void allBranchRetrievalAcrossBranches() {
        Branch first = createBranch("BRKG1-" + uniqueKey("B"));
        Branch second = createBranch("BRKG2-" + uniqueKey("B"));
        Item item = newItem("cross branch item");
        long firstPurchase = insertBatch(first, item, 1000, StockBatchSourceType.PURCHASE, "30.00");
        long secondProcessing = insertBatch(second, item, 1000, StockBatchSourceType.PROCESSING, "31.00");
        long secondPurchase = insertBatch(second, item, 1000, StockBatchSourceType.PURCHASE, "32.00");

        setReceivedAt(firstPurchase, LocalDateTime.of(2026, 1, 1, 12, 0));
        setReceivedAt(secondPurchase, LocalDateTime.of(2026, 2, 1, 12, 0));

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesAcrossBranches(
                List.of(item.getId()));

        // PROCESSING first regardless of branch, then received time across
        // branches, then id.
        assertThat(batchIds(rows, item.getId())).containsExactly(secondProcessing, firstPurchase, secondPurchase);
    }

    // ---------------------------------------------------------------
    // H. No-batch items
    // ---------------------------------------------------------------

    @Test
    @DisplayName("items without positive batches produce no projection entries at all")
    void itemsWithoutPositiveBatchesProduceNoEntries() {
        Branch main = createBranch("BRKH-" + uniqueKey("B"));
        Item neverStocked = newItem("never stocked");
        Item zeroOnly = newItem("zero only");
        insertBatch(main, zeroOnly, 0, StockBatchSourceType.PURCHASE, "30.00");
        Item negativeOnly = newItem("negative only");
        insertBatch(main, negativeOnly, -1000, StockBatchSourceType.OVERRIDE, "30.00");

        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(
                List.of(neverStocked.getId(), zeroOnly.getId(), negativeOnly.getId()), main.getId());

        // No rows are fabricated for these items; the assembler associates
        // them with an empty list.
        assertThat(rows).isEmpty();
        assertThat(stockBatchRepository.findAvailableBatchesAcrossBranches(
                List.of(neverStocked.getId(), zeroOnly.getId(), negativeOnly.getId()))).isEmpty();
    }

    // ---------------------------------------------------------------
    // I. Legacy method equivalence
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk results equal findAvailableBatchesForScope for every fixture item and scope")
    void bulkRetrievalMatchesLegacyPerItemMethodExactly() {
        Branch first = createBranch("BRKI1-" + uniqueKey("B"));
        Branch second = createBranch("BRKI2-" + uniqueKey("B"));

        Item plain = newItem("plain item");
        long plainA = insertBatch(first, plain, 2000, StockBatchSourceType.PURCHASE, "20.00");
        long plainB = insertBatch(first, plain, 5000, StockBatchSourceType.PURCHASE, "35.00");
        Item mixed = newItem("mixed ledger item");
        long mixedPositive = insertBatch(first, mixed, 3000, StockBatchSourceType.PURCHASE, "30.00");
        insertBatch(first, mixed, 0, StockBatchSourceType.PURCHASE, "30.00");
        insertBatch(first, mixed, -2000, StockBatchSourceType.OVERRIDE, "30.00");
        Item sourced = newItem("mixed source item");
        long sourcedPurchase = insertBatch(first, sourced, 1000, StockBatchSourceType.PURCHASE, "28.00");
        long sourcedProcessing = insertBatch(first, sourced, 1000, StockBatchSourceType.PROCESSING, "29.00");
        long sourcedOverride = insertBatch(first, sourced, 1000, StockBatchSourceType.OVERRIDE, "27.00");
        Item crossBranch = newItem("cross branch item");
        long crossFirst = insertBatch(first, crossBranch, 4000, StockBatchSourceType.PURCHASE, "26.00");
        long crossSecond = insertBatch(second, crossBranch, 6000, StockBatchSourceType.PURCHASE, "25.00");
        Item expiring = newItem("expiring item");
        long expiringBatch = insertBatch(first, expiring, 900, StockBatchSourceType.PURCHASE, "24.00");
        setExpiry(expiringBatch, LocalDateTime.of(2026, 12, 31, 0, 0));
        Item neverStocked = newItem("never stocked item");

        // Distinct received times so the legacy ordering is exercised. Every
        // positive row is stamped — leaving one on its @CreationTimestamp
        // clock would make the expected order depend on the run time.
        setReceivedAt(plainA, LocalDateTime.of(2025, 12, 1, 12, 0));
        setReceivedAt(plainB, LocalDateTime.of(2026, 1, 1, 12, 0));
        setReceivedAt(mixedPositive, LocalDateTime.of(2026, 1, 2, 12, 0));
        setReceivedAt(sourcedPurchase, LocalDateTime.of(2026, 1, 3, 12, 0));
        setReceivedAt(sourcedProcessing, LocalDateTime.of(2026, 1, 4, 12, 0));
        setReceivedAt(sourcedOverride, LocalDateTime.of(2026, 1, 5, 12, 0));
        setReceivedAt(crossFirst, LocalDateTime.of(2026, 1, 6, 12, 0));
        setReceivedAt(crossSecond, LocalDateTime.of(2026, 1, 7, 12, 0));
        setReceivedAt(expiringBatch, LocalDateTime.of(2026, 1, 8, 12, 0));

        List<Long> allIds = List.of(plain.getId(), mixed.getId(), sourced.getId(),
                crossBranch.getId(), expiring.getId(), neverStocked.getId());

        // Concrete-branch scope for both branches.
        for (Branch branch : List.of(first, second)) {
            Map<Long, List<ItemStockBatch>> bulk = byItemOrdered(
                    stockBatchRepository.findAvailableBatchesForBranch(allIds, branch.getId()));
            for (Long itemId : allIds) {
                List<StockBatch> legacy =
                        stockBatchRepository.findAvailableBatchesForScope(branch.getId(), itemId);
                assertMatchesLegacy(bulk.getOrDefault(itemId, List.of()), legacy, branch.getId());
            }
        }

        // All-branch scope.
        Map<Long, List<ItemStockBatch>> bulkAcross = byItemOrdered(
                stockBatchRepository.findAvailableBatchesAcrossBranches(allIds));
        for (Long itemId : allIds) {
            List<StockBatch> legacy = stockBatchRepository.findAvailableBatchesForScope(null, itemId);
            assertMatchesLegacy(bulkAcross.getOrDefault(itemId, List.of()), legacy, null);
        }

        // Sanity: the fixtures actually exercise positive rows somewhere.
        assertThat(batchIds(bulkAcross.get(plain.getId()), plain.getId())).containsExactly(plainA, plainB);
        assertThat(batchIds(bulkAcross.get(mixed.getId()), mixed.getId())).containsExactly(mixedPositive);
        assertThat(batchIds(bulkAcross.get(sourced.getId()), sourced.getId()))
                .containsExactly(sourcedProcessing, sourcedPurchase, sourcedOverride);
        assertThat(batchIds(bulkAcross.get(crossBranch.getId()), crossBranch.getId()))
                .containsExactly(crossFirst, crossSecond);
    }

    // ---------------------------------------------------------------
    // J. Query count
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one bulk query per scope, not one query per item")
    void bulkRetrievalRunsAsOneQueryPerScope() {
        Branch main = createBranch("BRKJ-" + uniqueKey("B"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Item item = newItem("query count item " + i);
            insertBatch(main, item, 1000 + i, StockBatchSourceType.PURCHASE, "30.00");
            insertBatch(main, item, 500, StockBatchSourceType.PROCESSING, "31.00");
            ids.add(item.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        List<ItemStockBatch> branchRows = stockBatchRepository.findAvailableBatchesForBranch(ids, main.getId());
        assertThat(branchRows).hasSize(50);
        assertThat(statistics.getQueryExecutionCount())
                .as("25 items must cost one query, not 25")
                .isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        List<ItemStockBatch> allRows = stockBatchRepository.findAvailableBatchesAcrossBranches(ids);
        assertThat(allRows).hasSize(50);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // K. Empty input
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an empty item ID collection returns an empty result without invalid SQL")
    void emptyItemIdCollectionIsSafe() {
        Branch main = createBranch("BRKK-" + uniqueKey("B"));
        Statistics statistics = statistics();

        // Convention: service-level empty guards (PromotionGate.limitStates).
        // The repository stays safe if called anyway — Hibernate renders the
        // empty IN list as a query matching no rows, one execution, empty
        // result. Callers must not rely on that for performance.
        statistics.clear();
        assertThat(stockBatchRepository.findAvailableBatchesForBranch(List.of(), main.getId())).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);

        statistics.clear();
        assertThat(stockBatchRepository.findAvailableBatchesAcrossBranches(List.of())).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // L. Large ID list
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a 6,000-element IN list stays a single query and returns only real rows")
    void largeItemIdListsStayOneQuery() {
        Branch main = createBranch("BRKL-" + uniqueKey("B"));
        Item real = newItem("real item");
        long realBatch = insertBatch(main, real, 4000, StockBatchSourceType.PURCHASE, "30.00");

        // Parameter-binding and result behavior at catalogue scale; the
        // 6,000 non-existent ids simply match no rows.
        List<Long> ids = new ArrayList<>();
        for (long id = 10_000_000L; id < 10_006_000L; id++) {
            ids.add(id);
        }
        ids.add(real.getId());

        Statistics statistics = statistics();
        statistics.clear();
        List<ItemStockBatch> rows = stockBatchRepository.findAvailableBatchesForBranch(ids, main.getId());

        assertThat(batchIds(rows, real.getId())).containsExactly(realBatch);
        assertThat(rows).hasSize(1);
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be one query")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Field-by-field + exact-order comparison against the legacy method. */
    private void assertMatchesLegacy(List<ItemStockBatch> bulk, List<StockBatch> legacy, Long scopeBranchId) {
        assertThat(bulk).as("batch count for scope %s", scopeBranchId).hasSize(legacy.size());
        for (int i = 0; i < legacy.size(); i++) {
            ItemStockBatch row = bulk.get(i);
            StockBatch entity = legacy.get(i);
            assertThat(row.batchId()).as("batch order at %s", i).isEqualTo(entity.getId());
            assertThat(row.itemId()).isEqualTo(entity.getItem().getId());
            assertThat(row.sellingPrice()).isEqualByComparingTo(entity.getSellingPrice());
            assertThat(row.quantity()).isEqualTo(entity.getQuantity());
            assertThat(row.expireDate()).isEqualTo(entity.getExpireDate());
            assertThat(row.sourceType()).isEqualTo(entity.getSourceType());
            assertThat(row.receivedAt()).isEqualTo(entity.getReceivedAt());
        }
    }

    private Map<Long, List<ItemStockBatch>> byItemOrdered(Collection<ItemStockBatch> rows) {
        Map<Long, List<ItemStockBatch>> byItem = new LinkedHashMap<>();
        for (ItemStockBatch row : rows) {
            byItem.computeIfAbsent(row.itemId(), key -> new ArrayList<>()).add(row);
        }
        return byItem;
    }

    private List<Long> batchIds(Collection<ItemStockBatch> rows, long itemId) {
        List<Long> ids = new ArrayList<>();
        for (ItemStockBatch row : rows) {
            if (row.itemId() == itemId) {
                ids.add(row.batchId());
            }
        }
        return ids;
    }

    private Map<Long, ItemStockBatch> byBatch(Collection<ItemStockBatch> rows) {
        Map<Long, ItemStockBatch> byBatch = new LinkedHashMap<>();
        for (ItemStockBatch row : rows) {
            byBatch.put(row.batchId(), row);
        }
        return byBatch;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Item newItem(String name) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("BRK"))
                .name(name)
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .reorderLevel(0)
                .build());
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

    /** Seeds one positive batch row in raw base units. */
    private long insertBatch(Branch branch, Item item, int quantity, StockBatchSourceType sourceType,
            String sellingPrice) {
        return stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(quantity)
                .originalQuantity(Math.abs(quantity))
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal(sellingPrice))
                .sourceType(sourceType)
                .batchCode(uniqueKey("SEED"))
                .build()).getId();
    }

    /** @CreationTimestamp overrides supplied values on insert — write the
     *  deterministic ordering timestamps afterwards over JDBC, the same
     *  technique NegativeStockWorkflowIntegrationTest uses. */
    private void setReceivedAt(long batchId, LocalDateTime receivedAt) {
        jdbcTemplate.update("UPDATE stock_batches SET received_at = ? WHERE id = ?", receivedAt, batchId);
    }

    private void setExpiry(long batchId, LocalDateTime expireDate) {
        jdbcTemplate.update("UPDATE stock_batches SET expire_date = ? WHERE id = ?", expireDate, batchId);
    }
}
