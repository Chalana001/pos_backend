package com.chala.posapp.integration;

import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.dto.stock.ItemStockBatch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-007A: verification of the bulk stock queries
 * ({@code aggregateStockForBranch} / {@code aggregateStockAcrossBranches} /
 * {@code findAvailableBatchesForBranch} / {@code findAvailableBatchesAcrossBranches})
 * against real MySQL through Testcontainers. The TASK-006/007 suites prove
 * equivalence on H2; this class re-proves it on the production database and
 * records the behavior H2 cannot speak for: SUM type widening (MySQL DECIMAL),
 * large IN-list binding, and actual optimizer plans.
 *
 * <p>Equivalence claims are identical to the H2 suites: the bulk results must
 * equal the legacy per-item methods field by field on the same fixtures and
 * scopes, and the positive-batch rows must keep the legacy per-item order
 * (PROCESSING first, then {@code receivedAt ASC}, then {@code id ASC}).
 * Every {@code received_at} is written deterministically over JDBC after
 * insert ({@code @CreationTimestamp} overrides supplied values), so no
 * assertion depends on insert order or wall-clock time.
 *
 * <p>The EXPLAIN tests are diagnostic: they run EXPLAIN and EXPLAIN ANALYZE
 * over the exact SQL Hibernate sent (captured by the test-only
 * {@link SqlCaptureStatementInspector}, with parameter markers inlined as
 * literals and the table qualified with the {@code pos_db} catalog the raw
 * JDBC connection lacks) and record index / access type / rows / filesort.
 * Plans are printed and only sanity-asserted — a tiny fixture is not a
 * production workload, and these tests are not a reason to add indexes.
 *
 * <p>Query-count assertions use Hibernate statistics with the counter cleared
 * immediately before the measured repository call, so fixture setup, legacy
 * comparison queries and EXPLAIN commands are never counted.
 */
@ActiveProfiles(profiles = {"tc"}, inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.chala.posapp.integration.SqlCaptureStatementInspector"
})
class StockBatchBulkQueriesMysqlIntegrationTest extends ApiIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_master")
            .withUsername("posapp")
            .withPassword("posapp")
            // pos_db must exist before the app boots: raw connections anchor on
            // it and the tenant migration chain runs over it.
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tc-init/create-legacy-db.sql"),
                    "/docker-entrypoint-initdb.d/create-legacy-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false");
        // root, not the container user: the app provisions tenant databases at
        // runtime and the URL carries createDatabaseIfNotExist, both of which
        // need server-wide rights the per-database app user does not have.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------
    // 0. Environment: these tests really ran against MySQL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("the suite is talking to real MySQL 8 in the pos_db tenant schema")
    void environmentIsRealMySqlInPosDb() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertThat(version)
                .as("this class must not silently pass on H2")
                .startsWith("8.");
        Integer tenantTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'pos_db'",
                Integer.class);
        assertThat(tenantTables).isPositive();
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pos_db.flyway_schema_history", Integer.class);
        assertThat(migrations)
                .as("the tenant migration chain must have run over pos_db")
                .isPositive();
    }

    // ---------------------------------------------------------------
    // 1. Aggregation equivalence vs the legacy per-item methods
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk aggregates equal the legacy per-item totals and existence checks on MySQL")
    void bulkAggregatesMatchLegacyPerItemMethodsOnMySql() {
        Branch first = createBranch("MYAG1-" + uniqueKey("B"));
        Branch second = createBranch("MYAG2-" + uniqueKey("B"));

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
                assertAggregateMatchesLegacy(aggregate, branch.getId());
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
            assertAggregateMatchesLegacy(aggregate, null);
        }

        // Sanity: the fixtures produced the expected shapes somewhere.
        assertThat(byItem(stockBatchRepository.aggregateStockForBranch(allIds, first.getId()))
                .get(mixed.getId()).totalQuantity()).isEqualTo(7000L);
        assertThat(across.get(crossBranch.getId()).totalQuantity()).isEqualTo(9000L);
    }

    // ---------------------------------------------------------------
    // 2. Positive-batch equivalence vs findAvailableBatchesForScope
    // ---------------------------------------------------------------

    @Test
    @DisplayName("bulk positive-batch rows equal findAvailableBatchesForScope on MySQL, field by field and in order")
    void bulkPositiveBatchesMatchLegacyPerItemMethodOnMySql() {
        Branch first = createBranch("MYBK1-" + uniqueKey("B"));
        Branch second = createBranch("MYBK2-" + uniqueKey("B"));

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

        // Deterministic ordering inputs: every positive row is stamped after
        // insert, so no assertion depends on @CreationTimestamp wall time.
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
                assertBatchesMatchLegacy(bulk.getOrDefault(itemId, List.of()), legacy, branch.getId());
            }
        }

        // All-branch scope.
        Map<Long, List<ItemStockBatch>> bulkAcross = byItemOrdered(
                stockBatchRepository.findAvailableBatchesAcrossBranches(allIds));
        for (Long itemId : allIds) {
            List<StockBatch> legacy = stockBatchRepository.findAvailableBatchesForScope(null, itemId);
            assertBatchesMatchLegacy(bulkAcross.getOrDefault(itemId, List.of()), legacy, null);
        }

        // Sanity on the ordering rules themselves: zero/negative excluded,
        // PROCESSING first, receivedAt ascending inside each priority group.
        assertThat(batchIds(bulkAcross, plain.getId())).containsExactly(plainA, plainB);
        assertThat(batchIds(bulkAcross, mixed.getId())).containsExactly(mixedPositive);
        assertThat(batchIds(bulkAcross, sourced.getId()))
                .containsExactly(sourcedProcessing, sourcedPurchase, sourcedOverride);
        assertThat(batchIds(bulkAcross, crossBranch.getId())).containsExactly(crossFirst, crossSecond);
    }

    // ---------------------------------------------------------------
    // 3. Long projection beyond Integer.MAX_VALUE
    // ---------------------------------------------------------------

    @Test
    @DisplayName("sums beyond the int range survive MySQL's DECIMAL SUM as exact Long values")
    void sumsBeyondIntegerRangeAreExactLongsOnMySql() {
        Branch main = createBranch("MYLG-" + uniqueKey("B"));

        // quantity is an int column; each row is a valid int, the SUM is not.
        // MySQL widens SUM(int) to DECIMAL — the projection must carry it as
        // Long without truncation. The legacy getTotalQuantityByItemAndScope
        // returns Integer and cannot represent such sums; that legacy
        // limitation is documented separately and deliberately NOT asserted
        // here (the legacy method is not changed to make this test pass).
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

        assertThat(byItem.get(wide.getId()).totalQuantity())
                .isInstanceOf(Long.class)
                .isEqualTo(4_000_000_000L);
        assertThat(byItem.get(deepNegative.getId()).totalQuantity()).isEqualTo(-4_000_000_000L);
        assertThat(byItem.get(cancelling.getId()).totalQuantity()).isZero();

        // Counts are plain LONG/COUNT results and stay exact as well.
        assertThat(byItem.get(wide.getId()).batchCount()).isEqualTo(2L);
        assertThat(byItem.get(deepNegative.getId()).negativeBatchCount()).isEqualTo(2L);
        assertThat(byItem.get(cancelling.getId()).negativeBatchCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // 4. Large IN-list binding
    // ---------------------------------------------------------------

    @Test
    @DisplayName("a 6,001-element IN list binds and executes on MySQL, returning only real rows")
    void largeInListsBindOnMySql() {
        Branch main = createBranch("MYIN-" + uniqueKey("B"));
        Item real = newItem("real item");
        long realBatch = insertBatch(main, real, 4000, StockBatchSourceType.PURCHASE, "30.00");
        insertBatch(main, real, -1);

        // 6,000 non-matching filler ids plus the one real item, mirroring the
        // catalogue scale these queries are built for. Binding and result
        // behavior only — no performance claim is made or measurable here.
        List<Long> ids = new ArrayList<>();
        for (long id = 10_000_000L; id < 10_006_000L; id++) {
            ids.add(id);
        }
        ids.add(real.getId());

        Statistics statistics = statistics();
        statistics.clear();
        List<ItemStockAggregate> aggregates = stockBatchRepository.aggregateStockForBranch(ids, main.getId());
        assertThat(aggregates).hasSize(1);
        assertThat(aggregates.get(0).itemId()).isEqualTo(real.getId());
        // The -1 row participates in the net (4000 - 1) but must stay out of
        // the positive-batch lists below.
        assertThat(aggregates.get(0).totalQuantity()).isEqualTo(3999L);
        assertThat(aggregates.get(0).negativeBatchCount()).isEqualTo(1L);
        assertThat(statistics.getQueryExecutionCount())
                .as("6,001 ids must still be one query")
                .isEqualTo(1);

        statistics.clear();
        List<ItemStockBatch> batches = stockBatchRepository.findAvailableBatchesForBranch(ids, main.getId());
        assertThat(batchIds(batches, real.getId())).containsExactly(realBatch);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);

        statistics.clear();
        assertThat(stockBatchRepository.aggregateStockAcrossBranches(ids)).hasSize(1);
        statistics.clear();
        List<ItemStockBatch> across = stockBatchRepository.findAvailableBatchesAcrossBranches(ids);
        assertThat(batchIds(across, real.getId())).containsExactly(realBatch);
    }

    // ---------------------------------------------------------------
    // 5. EXPLAIN / EXPLAIN ANALYZE for the four queries
    // ---------------------------------------------------------------

    @Test
    @DisplayName("EXPLAIN and EXPLAIN ANALYZE record the plans of the four bulk queries")
    void explainPlansForTheFourBulkQueries() {
        Branch first = createBranch("MYEX1-" + uniqueKey("B"));
        Branch second = createBranch("MYEX2-" + uniqueKey("B"));
        // Enough rows that the plan is not chosen over a single-row table.
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Item item = newItem("explain item " + i);
            insertBatch(first, item, 1000 + i, StockBatchSourceType.PURCHASE, "30.00");
            insertBatch(second, item, 500, StockBatchSourceType.PROCESSING, "31.00");
            ids.add(item.getId());
        }
        List<Long> probeIds = List.of(ids.get(0), ids.get(1), ids.get(2));

        String aggregateBranchSql = captureSql(
                () -> stockBatchRepository.aggregateStockForBranch(probeIds, first.getId()));
        String aggregateAllSql = captureSql(
                () -> stockBatchRepository.aggregateStockAcrossBranches(probeIds));
        String batchesBranchSql = captureSql(
                () -> stockBatchRepository.findAvailableBatchesForBranch(probeIds, first.getId()));
        String batchesAllSql = captureSql(
                () -> stockBatchRepository.findAvailableBatchesAcrossBranches(probeIds));

        // The intended item and branch filtering is visible in the SQL that
        // was really sent — the all-branch variants must carry no branch
        // predicate, and the batch variants must keep the quantity > 0 filter.
        assertThat(normalized(aggregateBranchSql))
                .contains("branch_id=", "item_id", "groupby");
        assertThat(normalized(aggregateAllSql))
                .doesNotContain("branch_id=")
                .contains("item_id", "groupby");
        assertThat(normalized(batchesBranchSql))
                .contains("branch_id=", "item_id", "quantity>0", "orderby", "casewhen");
        assertThat(normalized(batchesAllSql))
                .doesNotContain("branch_id=")
                .contains("item_id", "quantity>0", "orderby", "casewhen");

        Map<String, String> plans = new LinkedHashMap<>();
        plans.put("concrete-branch aggregation", qualify(aggregateBranchSql));
        plans.put("all-branch aggregation", qualify(aggregateAllSql));
        plans.put("concrete positive-batch retrieval", qualify(batchesBranchSql));
        plans.put("all-branch positive-batch retrieval", qualify(batchesAllSql));

        for (Map.Entry<String, String> entry : plans.entrySet()) {
            List<Object> binds = entry.getKey().startsWith("concrete")
                    ? List.of(first.getId(), probeIds.get(0), probeIds.get(1), probeIds.get(2))
                    : List.of(probeIds.get(0), probeIds.get(1), probeIds.get(2));
            String literalSql = inlineBinds(entry.getValue(), binds);

            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + literalSql);
            printPlan("EXPLAIN " + entry.getKey(), plan);
            assertThat(plan)
                    .as("EXPLAIN must produce a plan row for %s", entry.getKey())
                    .isNotEmpty();

            explainAnalyze("EXPLAIN ANALYZE " + entry.getKey(), literalSql);
        }
    }

    // ---------------------------------------------------------------
    // 6. Query counts: one statement per bulk call
    // ---------------------------------------------------------------

    @Test
    @DisplayName("each bulk method issues exactly one SQL statement per call on MySQL")
    void bulkMethodsRunAsOneStatementPerCall() {
        Branch main = createBranch("MYQC-" + uniqueKey("B"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Item item = newItem("query count item " + i);
            insertBatch(main, item, 1000 + i, StockBatchSourceType.PURCHASE, "30.00");
            insertBatch(main, item, 500, StockBatchSourceType.PROCESSING, "31.00");
            ids.add(item.getId());
        }
        Statistics statistics = statistics();

        statistics.clear();
        SqlCaptureStatementInspector.clear();
        assertThat(stockBatchRepository.aggregateStockForBranch(ids, main.getId())).hasSize(25);
        assertOneStatement(statistics, "aggregateStockForBranch");

        statistics.clear();
        SqlCaptureStatementInspector.clear();
        assertThat(stockBatchRepository.aggregateStockAcrossBranches(ids)).hasSize(25);
        assertOneStatement(statistics, "aggregateStockAcrossBranches");

        statistics.clear();
        SqlCaptureStatementInspector.clear();
        assertThat(stockBatchRepository.findAvailableBatchesForBranch(ids, main.getId())).hasSize(50);
        assertOneStatement(statistics, "findAvailableBatchesForBranch");

        statistics.clear();
        SqlCaptureStatementInspector.clear();
        assertThat(stockBatchRepository.findAvailableBatchesAcrossBranches(ids)).hasSize(50);
        assertOneStatement(statistics, "findAvailableBatchesAcrossBranches");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Query-count assertion for one measured bulk call. */
    private void assertOneStatement(Statistics statistics, String method) {
        assertThat(statistics.getQueryExecutionCount())
                .as("%s must cost one query, not one per item", method)
                .isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(SqlCaptureStatementInspector.captured())
                .as("%s must send exactly one SQL statement", method)
                .hasSize(1);
    }

    /** The legacy aggregate equivalence check for one row and one scope. */
    private void assertAggregateMatchesLegacy(ItemStockAggregate aggregate, Long scopeBranchId) {
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

    /** Field-by-field + exact-order comparison against the legacy batch query. */
    private void assertBatchesMatchLegacy(List<ItemStockBatch> bulk, List<StockBatch> legacy, Long scopeBranchId) {
        assertThat(bulk).as("batch count for scope %s", scopeBranchId).hasSize(legacy.size());
        for (int i = 0; i < legacy.size(); i++) {
            ItemStockBatch row = bulk.get(i);
            StockBatch entity = legacy.get(i);
            assertThat(row.batchId()).as("batch order at %s for scope %s", i, scopeBranchId)
                    .isEqualTo(entity.getId());
            assertThat(row.itemId()).isEqualTo(entity.getItem().getId());
            assertThat(row.sellingPrice()).isEqualByComparingTo(entity.getSellingPrice());
            assertThat(row.quantity()).isEqualTo(entity.getQuantity());
            assertThat(row.expireDate()).isEqualTo(entity.getExpireDate());
            assertThat(row.sourceType()).isEqualTo(entity.getSourceType());
            assertThat(row.receivedAt()).isEqualTo(entity.getReceivedAt());
        }
    }

    /** Runs one repository call and returns the single SQL statement it sent. */
    private String captureSql(Runnable call) {
        SqlCaptureStatementInspector.clear();
        call.run();
        List<String> statements = SqlCaptureStatementInspector.captured();
        assertThat(statements).hasSize(1);
        return statements.get(0);
    }

    /** Lowercased, whitespace-free copy of the SQL for predicate assertions. */
    private String normalized(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Hibernate emits bare table names; raw JDBC here has no default catalog. */
    private String qualify(String sql) {
        return sql.replace("stock_batches", "pos_db.stock_batches");
    }

    /**
     * Replaces the parameter markers with literal values, in text order — the
     * same order Hibernate binds them in. If the SQL carries one marker more
     * than the known value list, the extra marker is the ORDER BY CASE enum
     * literal ({@code PROCESSING}); any other mismatch fails loudly rather
     * than EXPLAIN-ing a misbound statement.
     */
    private String inlineBinds(String sql, List<Object> binds) {
        List<Object> values = new ArrayList<>(binds);
        long marks = sql.chars().filter(c -> c == '?').count();
        if (marks == values.size() + 1) {
            values.add(StockBatchSourceType.PROCESSING.name());
        } else if (marks != values.size()) {
            throw new IllegalStateException(
                    "SQL has " + marks + " parameter markers but " + values.size()
                            + " values are known; cannot inline safely: " + sql);
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') {
                Object value = values.get(index++);
                out.append(value instanceof Number ? value.toString() : "'" + value + "'");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void explainAnalyze(String label, String literalSql) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN ANALYZE " + literalSql);
            printPlan(label, rows);
            assertThat(rows).isNotEmpty();
        } catch (RuntimeException ex) {
            // EXPLAIN ANALYZE needs MySQL >= 8.0.18. The plain EXPLAIN above is
            // the mandatory half; record the gap instead of failing the suite.
            System.out.println("[PLAN] " + label + " unsupported: " + ex.getMessage());
        }
    }

    private void printPlan(String label, List<Map<String, Object>> planRows) {
        System.out.println("[PLAN] " + label);
        for (Map<String, Object> row : planRows) {
            System.out.println("[PLAN]   " + row);
        }
    }

    private Map<Long, ItemStockAggregate> byItem(List<ItemStockAggregate> rows) {
        Map<Long, ItemStockAggregate> byItem = new LinkedHashMap<>();
        for (ItemStockAggregate row : rows) {
            assertThat(byItem.put(row.itemId(), row))
                    .as("item %s aggregated twice — the query must group by item", row.itemId())
                    .isNull();
        }
        return byItem;
    }

    private Map<Long, List<ItemStockBatch>> byItemOrdered(List<ItemStockBatch> rows) {
        Map<Long, List<ItemStockBatch>> byItem = new LinkedHashMap<>();
        for (ItemStockBatch row : rows) {
            byItem.computeIfAbsent(row.itemId(), key -> new ArrayList<>()).add(row);
        }
        return byItem;
    }

    private List<Long> batchIds(Map<Long, List<ItemStockBatch>> byItem, long itemId) {
        List<Long> ids = new ArrayList<>();
        for (ItemStockBatch row : byItem.getOrDefault(itemId, List.of())) {
            ids.add(row.batchId());
        }
        return ids;
    }

    private List<Long> batchIds(List<ItemStockBatch> rows, long itemId) {
        List<Long> ids = new ArrayList<>();
        for (ItemStockBatch row : rows) {
            if (row.itemId() == itemId) {
                ids.add(row.batchId());
            }
        }
        return ids;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private Item newItem(String name) {
        return itemRepository.save(Item.builder()
                .barcode(uniqueKey("MYB"))
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
        return insertBatch(branch, item, quantity, StockBatchSourceType.PURCHASE, "30.00");
    }

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
                .batchCode(uniqueKey("MYSEED"))
                .build()).getId();
    }

    /**
     * @CreationTimestamp overrides supplied values on insert — write the
     * deterministic ordering timestamps afterwards over JDBC. The raw
     * connection has no default catalog (see resetLegacyTenantState), so the
     * table is qualified with the legacy schema name.
     */
    private void setReceivedAt(long batchId, LocalDateTime receivedAt) {
        jdbcTemplate.update("UPDATE pos_db.stock_batches SET received_at = ? WHERE id = ?", receivedAt, batchId);
    }

    private void setExpiry(long batchId, LocalDateTime expireDate) {
        jdbcTemplate.update("UPDATE pos_db.stock_batches SET expire_date = ? WHERE id = ?", expireDate, batchId);
    }
}
