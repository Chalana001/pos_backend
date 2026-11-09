package com.chala.posapp.integration;

import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.stock.StockBatch;
import com.chala.posapp.entity.stock.StockBatchSourceType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level verification of the bulk stock aggregation
 * ({@code StockBatchRepository.aggregateStockForBranch} /
 * {@code aggregateStockAcrossBranches}) against the legacy per-item stock
 * methods on identical fixtures and scopes.
 *
 * <p>Predicate audit: the legacy methods filter only on
 * {@code branch.id} / {@code item.id} (plus {@code quantity < 0} for the
 * negative check) — {@code StockBatch} has no deleted/cancelled/status/
 * source-type columns and no such predicates appear in any legacy stock
 * query, so "every row is applicable" is the exact existing behaviour and
 * the aggregate adds no filter of its own.
 *
 * <p>Runs on the H2 test profile (the same queries run on MySQL through
 * Hibernate's JPQL translation). MySQL EXPLAIN / EXPLAIN ANALYZE plan
 * verification is pending a Docker-enabled environment and is NOT claimed
 * here.
 *
 * <p>Legacy {@code getTotalQuantityByItemAndScope} returns {@code Integer};
 * the aggregate carries {@code Long} sums. Equivalence is asserted within the
 * int range; the Long-only precision cases are exercised separately against
 * the aggregate alone.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class StockBatchAggregateIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ---------------------------------------------------------------
    // A. Concrete branch aggregation over every ledger shape
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one concrete-branch aggregate per item: net, batch count and negative count over every ledger shape")
    void concreteBranchAggregationCoversEveryLedgerShape() {
        Branch main = createBranch("AGGA-" + uniqueKey("B"));
        // Positive-only: two rows.
        Item positive = newItem("positive-only");
        insertBatch(main, positive, 2000);
        insertBatch(main, positive, 5000);
        // Zero-only history.
        Item zero = newItem("zero-only");
        insertBatch(main, zero, 0);
        // Negative-only history.
        Item negative = newItem("negative-only");
        insertBatch(main, negative, -3000);
        // Mixed: positive, negative and zero rows alike.
        Item mixed = newItem("mixed");
        insertBatch(main, mixed, 10000);
        insertBatch(main, mixed, -3000);
        insertBatch(main, mixed, 0);
        // No batch history at all.
        newItem("no history");

        List<ItemStockAggregate> rows = stockBatchRepository.aggregateStockForBranch(
                List.of(positive.getId(), zero.getId(), negative.getId(), mixed.getId(), 999999L),
                main.getId());
        Map<Long, ItemStockAggregate> byItem = byItem(rows);

        // Positive-only: the net sums every positive row.
        assertThat(byItem.get(positive.getId()).totalQuantity()).isEqualTo(7000L);
        assertThat(byItem.get(positive.getId()).batchCount()).isEqualTo(2L);
        assertThat(byItem.get(positive.getId()).negativeBatchCount()).isZero();

        // Zero-only: the zero row still counts as history.
        assertThat(byItem.get(zero.getId()).totalQuantity()).isZero();
        assertThat(byItem.get(zero.getId()).batchCount()).isEqualTo(1L);
        assertThat(byItem.get(zero.getId()).negativeBatchCount()).isZero();

        // Negative-only.
        assertThat(byItem.get(negative.getId()).totalQuantity()).isEqualTo(-3000L);
        assertThat(byItem.get(negative.getId()).batchCount()).isEqualTo(1L);
        assertThat(byItem.get(negative.getId()).negativeBatchCount()).isEqualTo(1L);

        // Mixed: zero and negative rows all participate in the net and the count.
        assertThat(byItem.get(mixed.getId()).totalQuantity()).isEqualTo(7000L);
        assertThat(byItem.get(mixed.getId()).batchCount()).isEqualTo(3L);
        assertThat(byItem.get(mixed.getId()).negativeBatchCount()).isEqualTo(1L);

        // No history / unknown ids produce no row at all.
        assertThat(byItem).doesNotContainKeys(999999L);
    }

    // ---------------------------------------------------------------
    // B. All-branch aggregation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("all-branch aggregate sums, counts and flags across branches and groups by item")
    void allBranchAggregationSumsAcrossBranches() {
        Branch first = createBranch("AGGB1-" + uniqueKey("B"));
        Branch second = createBranch("AGGB2-" + uniqueKey("B"));

        Item shared = newItem("shared item");
        insertBatch(first, shared, 4000);
        insertBatch(second, shared, 6000);
        insertBatch(second, shared, -1000);

        // Positive stock in one branch must not hide a negative batch in another.
        Item hiddenNegative = newItem("hidden negative");
        insertBatch(first, hiddenNegative, 10000);
        insertBatch(second, hiddenNegative, -1);

        List<ItemStockAggregate> rows = stockBatchRepository.aggregateStockAcrossBranches(
                List.of(shared.getId(), hiddenNegative.getId()));
        Map<Long, ItemStockAggregate> byItem = byItem(rows);

        // Grouped by item: one row each.
        assertThat(rows).hasSize(2);

        assertThat(byItem.get(shared.getId()).totalQuantity()).isEqualTo(9000L);
        assertThat(byItem.get(shared.getId()).batchCount()).isEqualTo(3L);
        assertThat(byItem.get(shared.getId()).negativeBatchCount()).isEqualTo(1L);

        assertThat(byItem.get(hiddenNegative.getId()).totalQuantity()).isEqualTo(9999L);
        assertThat(byItem.get(hiddenNegative.getId()).batchCount()).isEqualTo(2L);
        assertThat(byItem.get(hiddenNegative.getId()).negativeBatchCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // C. No batch history
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an item without batch rows produces no aggregate row")
    void itemWithoutBatchHistoryProducesNoRow() {
        Branch main = createBranch("AGGC-" + uniqueKey("B"));
        Item neverStocked = newItem("never stocked");

        List<ItemStockAggregate> rows = stockBatchRepository.aggregateStockForBranch(
                List.of(neverStocked.getId()), main.getId());

        // A later assembler must read the absence of a row as "never stocked":
        // net 0, batch count 0, negative count 0 — exactly what the legacy
        // per-item methods answer for such an item (asserted in the
        // equivalence test).
        assertThat(rows).isEmpty();
    }

    // ---------------------------------------------------------------
    // D. Exact comparison against the legacy per-item methods
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk aggregates equal the legacy per-item totals and existence checks for every fixture item and scope")
    void bulkAggregatesMatchLegacyPerItemMethodsExactly() {
        Branch first = createBranch("AGGD1-" + uniqueKey("B"));
        Branch second = createBranch("AGGD2-" + uniqueKey("B"));

        Item positive = newItem("positive item");
        insertBatch(first, positive, 2000);
        insertBatch(first, positive, 5000);
        Item zero = newItem("zero item");
        insertBatch(first, zero, 0);
        Item negative = newItem("negative item");
        insertBatch(first, negative, -3000);
        Item mixed = newItem("mixed item");
        insertBatch(first, mixed, 10000);
        insertBatch(first, mixed, -3000);
        insertBatch(first, mixed, 0);
        Item crossBranch = newItem("cross branch item");
        insertBatch(first, crossBranch, 4000);
        insertBatch(second, crossBranch, 6000);
        insertBatch(second, crossBranch, -1000);
        Item neverStocked = newItem("never stocked item");

        List<Long> allIds = List.of(positive.getId(), zero.getId(), negative.getId(),
                mixed.getId(), crossBranch.getId(), neverStocked.getId());

        // Concrete-branch scope for each branch.
        for (Branch branch : List.of(first, second)) {
            Map<Long, ItemStockAggregate> byItem =
                    byItem(stockBatchRepository.aggregateStockForBranch(allIds, branch.getId()));
            for (Long itemId : allIds) {
                ItemStockAggregate aggregate = byItem.get(itemId);
                if (aggregate == null) {
                    // Absence of a row must equal the legacy answers for a
                    // never-stocked item in this scope.
                    assertThat(stockBatchRepository.getTotalQuantityByItemAndScope(branch.getId(), itemId))
                            .as("legacy total for %s at %s", itemId, branch.getId()).isZero();
                    assertThat(stockBatchRepository.existsBatchForScope(branch.getId(), itemId))
                            .as("legacy existence for %s at %s", itemId, branch.getId()).isFalse();
                    assertThat(stockBatchRepository.existsNegativeBatchForScope(branch.getId(), itemId))
                            .as("legacy negative existence for %s at %s", itemId, branch.getId()).isFalse();
                    continue;
                }
                assertMatchesLegacy(aggregate, branch.getId());
                // The concrete-branch total helper agrees with the scope helper.
                assertThat(aggregate.totalQuantity()).isEqualTo(
                        stockBatchRepository.getTotalQuantityByItemAndBranch(branch.getId(), itemId).longValue());
            }
        }

        // All-branch scope (what ItemService builds for branchId = 0).
        Map<Long, ItemStockAggregate> across =
                byItem(stockBatchRepository.aggregateStockAcrossBranches(allIds));
        for (Long itemId : allIds) {
            ItemStockAggregate aggregate = across.get(itemId);
            if (aggregate == null) {
                assertThat(stockBatchRepository.getTotalQuantityByItemAndScope(null, itemId))
                        .as("legacy total for %s across branches", itemId).isZero();
                assertThat(stockBatchRepository.existsBatchForScope(null, itemId))
                        .as("legacy existence for %s across branches", itemId).isFalse();
                continue;
            }
            assertMatchesLegacy(aggregate, null);
        }
    }

    // ---------------------------------------------------------------
    // E. Branch isolation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("concrete-branch aggregates never include another branch's rows")
    void concreteBranchQueriesNeverIncludeOtherBranchRows() {
        Branch first = createBranch("AGGE1-" + uniqueKey("B"));
        Branch second = createBranch("AGGE2-" + uniqueKey("B"));
        Item shared = newItem("shared item");
        insertBatch(first, shared, 4000);
        insertBatch(second, shared, 6000);
        insertBatch(second, shared, -1000);

        ItemStockAggregate firstScope =
                byItem(stockBatchRepository.aggregateStockForBranch(List.of(shared.getId()), first.getId()))
                        .get(shared.getId());
        ItemStockAggregate secondScope =
                byItem(stockBatchRepository.aggregateStockForBranch(List.of(shared.getId()), second.getId()))
                        .get(shared.getId());

        assertThat(firstScope.totalQuantity()).isEqualTo(4000L);
        assertThat(firstScope.batchCount()).isEqualTo(1L);
        assertThat(firstScope.negativeBatchCount()).isZero();

        assertThat(secondScope.totalQuantity()).isEqualTo(5000L);
        assertThat(secondScope.batchCount()).isEqualTo(2L);
        assertThat(secondScope.negativeBatchCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // F. Empty input
    // ---------------------------------------------------------------

    @Test
    @DisplayName("an empty item ID collection returns an empty result without invalid SQL")
    void emptyItemIdCollectionIsSafe() {
        Branch main = createBranch("AGGF-" + uniqueKey("B"));
        Statistics statistics = statistics();

        // The existing IN-list query (PromotionRepository.limitStateRaw) is
        // guarded by its SERVICE caller (PromotionGate.limitStates returns
        // early on empty input) — that is the project convention, and later
        // assemblers are expected to follow it. The repository must still be
        // safe when called with an empty collection: Hibernate renders the
        // empty IN list as a query matching no rows — one execution, no
        // "IN ()" syntax error, empty result.
        statistics.clear();
        assertThat(stockBatchRepository.aggregateStockForBranch(List.of(), main.getId())).isEmpty();
        assertThat(statistics.getQueryExecutionCount())
                .as("empty input executes one harmless query today; callers guard upstream")
                .isEqualTo(1);

        statistics.clear();
        assertThat(stockBatchRepository.aggregateStockAcrossBranches(List.of())).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a null branch id matches no rows instead of becoming the all-branch scope")
    void nullBranchIdIsNotAllBranchScope() {
        Branch main = createBranch("AGGN-" + uniqueKey("B"));
        Item item = newItem("scoped item");
        insertBatch(main, item, 4000);

        // The repository must not normalize null into all-branch aggregation:
        // branch_id = NULL matches nothing, and the all-branch aggregate above
        // remains a separate call.
        assertThat(stockBatchRepository.aggregateStockForBranch(List.of(item.getId()), null)).isEmpty();
        assertThat(stockBatchRepository.aggregateStockAcrossBranches(List.of(item.getId())))
                .singleElement()
                .extracting(ItemStockAggregate::totalQuantity)
                .isEqualTo(4000L);
    }

    // ---------------------------------------------------------------
    // G. Numeric precision
    // ---------------------------------------------------------------

    @Test
    @DisplayName("sums beyond the int range keep full long precision with no truncation or rounding")
    void sumsBeyondIntegerRangeArePreservedExactly() {
        Branch main = createBranch("AGGG-" + uniqueKey("B"));

        // quantity is an int column: each row fits in Integer, the SUM does not.
        Item wide = newItem("wide sum");
        insertBatch(main, wide, 2_000_000_000);
        insertBatch(main, wide, 2_000_000_000);

        Item deepNegative = newItem("deep negative");
        insertBatch(main, deepNegative, -2_000_000_000);
        insertBatch(main, deepNegative, -2_000_000_000);

        Item cancelling = newItem("cancelling offsets");
        insertBatch(main, cancelling, Integer.MAX_VALUE);
        insertBatch(main, cancelling, -Integer.MAX_VALUE);

        Map<Long, ItemStockAggregate> byItem = byItem(stockBatchRepository.aggregateStockForBranch(
                List.of(wide.getId(), deepNegative.getId(), cancelling.getId()), main.getId()));

        assertThat(byItem.get(wide.getId()).totalQuantity()).isEqualTo(4_000_000_000L);
        assertThat(byItem.get(deepNegative.getId()).totalQuantity()).isEqualTo(-4_000_000_000L);
        assertThat(byItem.get(cancelling.getId()).totalQuantity()).isZero();

        // Counts are exact too — nothing is rounded.
        assertThat(byItem.get(wide.getId()).batchCount()).isEqualTo(2L);
        assertThat(byItem.get(deepNegative.getId()).negativeBatchCount()).isEqualTo(2L);
        assertThat(byItem.get(cancelling.getId()).negativeBatchCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // H. Query count
    // ---------------------------------------------------------------

    @Test
    @DisplayName("one grouped query per scope, not one query per item")
    void aggregationRunsAsOneGroupedQueryPerScope() {
        Branch main = createBranch("AGGH-" + uniqueKey("B"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Item item = newItem("query count item " + i);
            insertBatch(main, item, 1000 + i);
            insertBatch(main, item, -1);
            ids.add(item.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        List<ItemStockAggregate> branchRows = stockBatchRepository.aggregateStockForBranch(ids, main.getId());
        assertThat(branchRows).hasSize(25);
        assertThat(statistics.getQueryExecutionCount())
                .as("25 items must cost one query, not 25")
                .isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        List<ItemStockAggregate> allRows = stockBatchRepository.aggregateStockAcrossBranches(ids);
        assertThat(allRows).hasSize(25);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 6,000-element IN list stays a single query and returns only real rows")
    void largeItemIdListsStayOneQuery() {
        Branch main = createBranch("AGGL-" + uniqueKey("B"));
        Item real = newItem("real item");
        insertBatch(main, real, 4000);

        // The expected tenant catalogue runs to 6,000+ items; most ids passed
        // to the aggregate may belong to items without batches in this scope.
        List<Long> ids = new ArrayList<>();
        for (long id = 10_000_000L; id < 10_006_000L; id++) {
            ids.add(id);
        }
        ids.add(real.getId());

        Statistics statistics = statistics();
        statistics.clear();
        List<ItemStockAggregate> rows = stockBatchRepository.aggregateStockForBranch(ids, main.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).itemId()).isEqualTo(real.getId());
        assertThat(rows.get(0).totalQuantity()).isEqualTo(4000L);
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be one query")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** The legacy equivalence check for one aggregate row and one scope. */
    private void assertMatchesLegacy(ItemStockAggregate aggregate, Long scopeBranchId) {
        long itemId = aggregate.itemId();
        assertThat(aggregate.totalQuantity())
                .as("total for %s at scope %s", itemId, scopeBranchId)
                .isEqualTo(stockBatchRepository.getTotalQuantityByItemAndScope(scopeBranchId, itemId).longValue());
        assertThat(aggregate.batchCount() > 0)
                .as("batch existence for %s at scope %s", itemId, scopeBranchId)
                .isEqualTo(stockBatchRepository.existsBatchForScope(scopeBranchId, itemId));
        assertThat(aggregate.negativeBatchCount() > 0)
                .as("negative existence for %s at scope %s", itemId, scopeBranchId)
                .isEqualTo(stockBatchRepository.existsNegativeBatchForScope(scopeBranchId, itemId));
    }

    private Map<Long, ItemStockAggregate> byItem(Collection<ItemStockAggregate> rows) {
        Map<Long, ItemStockAggregate> byItem = new HashMap<>();
        for (ItemStockAggregate row : rows) {
            assertThat(byItem.put(row.itemId(), row))
                    .as("item %s aggregated twice — the query must group by item", row.itemId())
                    .isNull();
        }
        return byItem;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Item newItem(String name) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("AGG"))
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

    /** Seeds one batch row in base units (quantity is the raw int column). */
    private long insertBatch(Branch branch, Item item, int quantity) {
        return stockBatchRepository.save(StockBatch.builder()
                .branch(branch)
                .item(item)
                .quantity(quantity)
                .originalQuantity(Math.abs(quantity))
                .costPrice(new BigDecimal("20.00"))
                .sellingPrice(new BigDecimal("30.00"))
                .sourceType(StockBatchSourceType.PURCHASE)
                .batchCode(uniqueKey("SEED"))
                .build()).getId();
    }
}
