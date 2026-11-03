package com.chala.posapp.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class NegativeStockReconciliationMigrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_db")
            .withUsername("posapp")
            .withPassword("posapp");

    @Test
    void normalizesEveryLedgerShapePreservesTotalsRowsAndIdsAndIsIdempotentOnReapply() throws Exception {
        String url = mysql.getJdbcUrl();
        migrate(url, "65");

        try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword())) {
            long branchA = insertBranch(connection, "REC-A");
            long branchB = insertBranch(connection, "REC-B");

            long negativeItem = insertItem(connection, "REC-NEGATIVE");
            long negativeOld = insertBatch(connection, branchA, negativeItem, -4, "30", "2026-01-01 09:00:00");
            long negativeNew = insertBatch(connection, branchA, negativeItem, -3, "30", "2026-01-02 09:00:00");
            long negativeSame = insertBatch(connection, branchA, negativeItem, 3, "30", "2026-01-03 09:00:00");
            long negativeOther = insertBatch(connection, branchA, negativeItem, 2, "35", "2026-01-04 09:00:00");

            long positiveItem = insertItem(connection, "REC-POSITIVE");
            long positiveNegative = insertBatch(connection, branchA, positiveItem, -5, "30", "2026-01-01 09:00:00");
            long positiveOld = insertBatch(connection, branchA, positiveItem, 3, "30", "2026-01-02 09:00:00");
            long positiveNew = insertBatch(connection, branchA, positiveItem, 4, "35", "2026-01-03 09:00:00");

            long priceOrderingItem = insertItem(connection, "REC-PRICE-ORDER");
            long priceOrderingNegative = insertBatch(connection, branchA, priceOrderingItem, -5, "30", "2026-01-01 09:00:00");
            long priceOrderingOldOther = insertBatch(connection, branchA, priceOrderingItem, 3, "35", "2026-01-02 09:00:00");
            long priceOrderingNewSame = insertBatch(connection, branchA, priceOrderingItem, 4, "30", "2026-01-03 09:00:00");

            long zeroItem = insertItem(connection, "REC-ZERO");
            long zeroNegative = insertBatch(connection, branchA, zeroItem, -5, "30", "2026-01-01 09:00:00");
            long zeroPositive = insertBatch(connection, branchA, zeroItem, 5, "30", "2026-01-02 09:00:00");

            long multiNegativeItem = insertItem(connection, "REC-MULTI-NEGATIVE");
            long multiOld = insertBatch(connection, branchA, multiNegativeItem, -5, "30", "2026-01-01 09:00:00");
            long multiNew = insertBatch(connection, branchA, multiNegativeItem, -5, "35", "2026-01-02 09:00:00");
            long multiSame = insertBatch(connection, branchA, multiNegativeItem, 3, "30", "2026-01-03 09:00:00");
            long multiOther = insertBatch(connection, branchA, multiNegativeItem, 5, "35", "2026-01-04 09:00:00");

            // Two negative rows survive the offset passes with balances left, so
            // consolidation must zero one and move its liability to the other.
            long consolidateItem = insertItem(connection, "REC-CONSOLIDATE");
            long consOld = insertBatch(connection, branchA, consolidateItem, -5, "30", "2026-01-01 09:00:00");
            long consNew = insertBatch(connection, branchA, consolidateItem, -3, "35", "2026-01-02 09:00:00");
            long consPositive = insertBatch(connection, branchA, consolidateItem, 2, "40", "2026-01-03 09:00:00");

            long branchItem = insertItem(connection, "REC-BRANCH");
            long branchNegative = insertBatch(connection, branchA, branchItem, -6, "40", "2026-01-01 09:00:00");
            long branchPositive = insertBatch(connection, branchB, branchItem, 6, "40", "2026-01-02 09:00:00");

            Map<Ledger, Long> totalsBefore = totals(connection);
            int rowsBefore = batchCount(connection);

            migrate(url, "66");

            assertEquals(0, quantity(connection, negativeOld));
            assertEquals(-2, quantity(connection, negativeNew));
            assertEquals(0, quantity(connection, negativeSame));
            assertEquals(0, quantity(connection, negativeOther));

            assertEquals(0, quantity(connection, positiveNegative));
            assertEquals(0, quantity(connection, positiveOld));
            assertEquals(2, quantity(connection, positiveNew));

            assertEquals(0, quantity(connection, priceOrderingNegative));
            assertEquals(0, quantity(connection, priceOrderingOldOther));
            assertEquals(2, quantity(connection, priceOrderingNewSame));

            assertEquals(0, quantity(connection, zeroNegative));
            assertEquals(0, quantity(connection, zeroPositive));

            assertEquals(-2, quantity(connection, multiOld));
            assertEquals(0, quantity(connection, multiNew));
            assertEquals(0, quantity(connection, multiSame));
            assertEquals(0, quantity(connection, multiOther));

            assertEquals(-6, quantity(connection, consOld));
            assertEquals(0, quantity(connection, consNew));
            assertEquals(0, quantity(connection, consPositive));

            assertEquals(-6, quantity(connection, branchNegative));
            assertEquals(6, quantity(connection, branchPositive));

            assertEquals(rowsBefore, batchCount(connection), "all batch rows and IDs must remain");
            assertTotalPreserved(connection, totalsBefore);
            assertOneNegative(connection, branchA, negativeItem);
            assertOneNegative(connection, branchA, multiNegativeItem);
            assertOneNegative(connection, branchA, consolidateItem);

            assertAudit(connection, negativeOld, negativeSame, "SAME_PRICE");
            assertAudit(connection, negativeNew, negativeOther, "ANY_PRICE");
            assertAudit(connection, positiveNegative, positiveOld, "POSITIVE_TOTAL_OLDEST");
            assertAudit(connection, positiveNegative, positiveNew, "POSITIVE_TOTAL_OLDEST");
            assertAudit(connection, priceOrderingNegative, priceOrderingOldOther, "POSITIVE_TOTAL_OLDEST");
            assertAudit(connection, multiNew, multiOther, "SAME_PRICE");
            assertAudit(connection, consOld, consPositive, "ANY_PRICE");

            // Row-scoped audit: the canonical row that kept a negative total and
            // every row consolidation or a zero total changed, with the exact
            // quantity before and after.
            assertRowAudit(connection, negativeNew, "NEGATIVE_TOTAL_CANONICAL", -2, -2);
            assertRowAudit(connection, multiOld, "NEGATIVE_TOTAL_CANONICAL", -2, -2);
            assertRowAudit(connection, branchNegative, "NEGATIVE_TOTAL_CANONICAL", -6, -6);
            assertRowAudit(connection, consOld, "NEGATIVE_TOTAL_CANONICAL", -3, -6);
            assertRowAudit(connection, consNew, "NEGATIVE_TOTAL_CANONICAL_ZERO", -3, 0);
            assertRowAudit(connection, zeroNegative, "ZERO_TOTAL_CLEAR", -5, 0);
            assertRowAudit(connection, zeroPositive, "ZERO_TOTAL_CLEAR", 5, 0);

            try (Statement statement = connection.createStatement();
                 ResultSet counts = statement.executeQuery("""
                         SELECT COUNT(*) AS runs, COALESCE(SUM(offsets_applied), 0) AS offsets
                         FROM stock_batch_reconciliation_runs
                         WHERE migration_version = 'V66'
                         """)) {
                assertTrue(counts.next());
                assertEquals(8, counts.getInt("runs"));
                assertEquals(10, counts.getInt("offsets"));
            }

            assertEquals(17, auditCount(connection));

            // Re-running V66 is not tested by migrating twice: Flyway skips a
            // version whose history row exists, so the second call never reaches
            // the migration logic. Remove only the V66 history row in this
            // disposable database and apply it again for real. The durable run
            // rows must make the re-application a no-op on every ledger.
            Map<Long, Integer> quantitiesAfterFirstRun = quantitiesById(connection);
            Map<String, Integer> auditByTypeAfterFirstRun = auditByMatchType(connection);

            deleteVersionHistoryRow(connection, "66");
            migrate(url, "66");

            assertEquals(quantitiesAfterFirstRun, quantitiesById(connection),
                    "re-applying V66 must not change any batch quantity or ID");
            assertEquals(auditByTypeAfterFirstRun, auditByMatchType(connection),
                    "re-applying V66 must not add duplicate audit rows");
            assertEquals(rowsBefore, batchCount(connection));
            assertEquals(8, runCount(connection), "re-applying V66 must not add duplicate run rows");
            assertTotalPreserved(connection, totalsBefore);
        }
    }

    private static void assertOneNegative(Connection connection, long branchId, long itemId) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM stock_batches
                WHERE branch_id = ? AND item_id = ? AND quantity < 0
                """)) {
            query.setLong(1, branchId);
            query.setLong(2, itemId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private static void assertAudit(
            Connection connection,
            long negativeBatchId,
            long positiveBatchId,
            String matchType
    ) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT quantity_offset, reason
                FROM stock_batch_reconciliation_offsets
                WHERE negative_batch_id = ? AND positive_batch_id = ? AND match_type = ?
                """)) {
            query.setLong(1, negativeBatchId);
            query.setLong(2, positiveBatchId);
            query.setString(3, matchType);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next(), "missing " + matchType + " audit row");
                assertTrue(result.getLong("quantity_offset") > 0);
                assertTrue(result.getString("reason").contains("V66"));
            }
        }
    }

    private static void assertRowAudit(
            Connection connection,
            long batchId,
            String matchType,
            long quantityBefore,
            long quantityAfter
    ) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM stock_batch_reconciliation_offsets
                WHERE (negative_batch_id = ? OR positive_batch_id = ?)
                  AND match_type = ?
                  AND quantity_before = ?
                  AND quantity_after = ?
                  AND quantity_offset = quantity_after - quantity_before
                """)) {
            query.setLong(1, batchId);
            query.setLong(2, batchId);
            query.setString(3, matchType);
            query.setLong(4, quantityBefore);
            query.setLong(5, quantityAfter);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1),
                        "expected one " + matchType + " audit row for batch " + batchId);
            }
        }
    }

    private static Map<Ledger, Long> totals(Connection connection) throws Exception {
        Map<Ledger, Long> totals = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT branch_id, item_id, SUM(quantity) AS total
                     FROM stock_batches
                     GROUP BY branch_id, item_id
                     """)) {
            while (result.next()) {
                totals.put(new Ledger(result.getLong("branch_id"), result.getLong("item_id")),
                        result.getLong("total"));
            }
        }
        return totals;
    }

    private static void assertTotalPreserved(Connection connection, Map<Ledger, Long> before) throws Exception {
        Map<Ledger, Long> after = totals(connection);
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<Ledger, Long> entry : before.entrySet()) {
            assertEquals(entry.getValue(), after.get(entry.getKey()),
                    "stock total changed for branch/item " + entry.getKey());
        }
    }

    private static Map<Long, Integer> quantitiesById(Connection connection) throws Exception {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, quantity FROM stock_batches ORDER BY id")) {
            while (result.next()) {
                quantities.put(result.getLong("id"), result.getInt("quantity"));
            }
        }
        return quantities;
    }

    private static Map<String, Integer> auditByMatchType(Connection connection) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT match_type, COUNT(*) AS row_count
                     FROM stock_batch_reconciliation_offsets
                     GROUP BY match_type
                     ORDER BY match_type
                     """)) {
            while (result.next()) {
                counts.put(result.getString("match_type"), result.getInt("row_count"));
            }
        }
        return counts;
    }

    private static int auditCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM stock_batch_reconciliation_offsets")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int runCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM stock_batch_reconciliation_runs WHERE migration_version = 'V66'")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void deleteVersionHistoryRow(Connection connection, String version) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM flyway_schema_history WHERE version = ?")) {
            delete.setString(1, version);
            assertEquals(1, delete.executeUpdate(), "exactly one history row must be removed");
        }
    }

    private static int batchCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM stock_batches")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int quantity(Connection connection, long batchId) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT quantity FROM stock_batches WHERE id = ?")) {
            query.setLong(1, batchId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next(), "batch " + batchId + " must be retained");
                return result.getInt(1);
            }
        }
    }

    private static long insertBranch(Connection connection, String code) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO branches (code, name, address, phone, active)
                VALUES (?, ?, 'Colombo', '0110000000', 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, code);
            insert.setString(2, code + " branch");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static long insertItem(Connection connection, String barcode) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO items (
                    active, barcode, cost_price, created_at, name, reorder_level,
                    selling_price, default_unit, item_type, is_kot_enabled,
                    pos_visible, stock_processing_enabled, overhead_cost_mode,
                    overhead_cost_value
                ) VALUES (1, ?, 10, NOW(6), ?, 0, 30, 'PCS', 'NORMAL', 0, 1, 0, 'NONE', 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, barcode);
            insert.setString(2, barcode + " item");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static long insertBatch(
            Connection connection,
            long branchId,
            long itemId,
            int quantity,
            String sellingPrice,
            String receivedAt
    ) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO stock_batches (
                    cost_price, original_quantity, quantity, received_at,
                    selling_price, branch_id, item_id, source_type, batch_code
                ) VALUES (10, ?, ?, ?, ?, ?, ?, 'PURCHASE', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, Math.abs(quantity));
            insert.setInt(2, quantity);
            insert.setTimestamp(3, java.sql.Timestamp.valueOf(receivedAt));
            insert.setBigDecimal(4, new BigDecimal(sellingPrice));
            insert.setLong(5, branchId);
            insert.setLong(6, itemId);
            insert.setString(7, "RECON-" + branchId + "-" + itemId + "-" + System.nanoTime());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "root", mysql.getPassword())
                .schemas(mysql.getDatabaseName())
                .defaultSchema(mysql.getDatabaseName())
                .locations("classpath:db/migration/tenant")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .mixed(true)
                .target(target)
                .load()
                .migrate();
    }

    private record Ledger(long branchId, long itemId) {
    }
}
