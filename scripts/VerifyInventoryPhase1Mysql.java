import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * Disposable MySQL verification for Inventory Phase 1. Run from backend/:
 *   java -cp "target/classes;target/phase1-classpath.txt" scripts/VerifyInventoryPhase1Mysql.java
 *
 * Linux separator is ':'. The script refuses non-local hosts and always drops
 * the uniquely named verification database in a finally block.
 */
public class VerifyInventoryPhase1Mysql {
    private static final String HOST = System.getProperty("phase1.mysql.host", "127.0.0.1");
    private static final String PORT = System.getProperty("phase1.mysql.port", "3306");
    private static final String USER = System.getProperty("phase1.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("phase1.mysql.password", "root");

    public static void main(String[] args) throws Exception {
        if (!HOST.equals("127.0.0.1") && !HOST.equals("localhost")) {
            throw new IllegalStateException("Refusing non-local MySQL host: " + HOST);
        }
        String db = "pos_phase1_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String server = "jdbc:mysql://" + HOST + ":" + PORT + "/?allowPublicKeyRetrieval=true&useSSL=false";
        String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + db
                + "?allowPublicKeyRetrieval=true&useSSL=false";

        try (Connection admin = DriverManager.getConnection(server, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }

        try {
            Flyway.configure()
                    .dataSource(url, USER, PASSWORD)
                    .schemas(db)
                    .defaultSchema(db)
                    .locations("classpath:db/migration/tenant")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .mixed(true)
                    .target(MigrationVersion.fromVersion("65"))
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                seed(statement);
                connection.commit();
                print(statement, "BEFORE V66 TOTALS", """
                        SELECT branch_id, item_id, SUM(quantity) total,
                               SUM(quantity * cost_price / 1000) valuation
                        FROM stock_batches GROUP BY branch_id, item_id ORDER BY branch_id, item_id
                        """);
                print(statement, "BEFORE V66 BATCHES", """
                        SELECT id, batch_code, quantity, original_quantity FROM stock_batches ORDER BY id
                        """);
            }

            Flyway.configure()
                    .dataSource(url, USER, PASSWORD)
                    .schemas(db)
                    .defaultSchema(db)
                    .locations("classpath:db/migration/tenant")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .mixed(true)
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                print(statement, "FLYWAY HISTORY", """
                        SELECT installed_rank, version, script, success
                        FROM flyway_schema_history ORDER BY installed_rank
                        """);
                print(statement, "V69 COLUMNS", """
                        SELECT column_name, column_type, is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'stock_adjustments'
                          AND column_name IN ('purchase_id','stock_batch_id',
                                              'reversal_of_adjustment_id','reversed_by_adjustment_id')
                        ORDER BY ordinal_position
                        """);
                print(statement, "AFTER V66 TOTALS", """
                        SELECT branch_id, item_id, SUM(quantity) total,
                               SUM(quantity * cost_price / 1000) valuation
                        FROM stock_batches GROUP BY branch_id, item_id ORDER BY branch_id, item_id
                        """);
                print(statement, "V66 RECONCILIATION RUNS", """
                        SELECT id, migration_version, branch_id, item_id,
                               total_before, total_after,
                               negative_before, negative_after,
                               positive_before, positive_after,
                               offsets_applied
                        FROM stock_batch_reconciliation_runs
                        WHERE migration_version = 'V66'
                        ORDER BY branch_id, item_id
                        """);
                print(statement, "FINAL NEGATIVE BATCHES", """
                        SELECT sb.id, sb.branch_id, sb.item_id, sb.batch_code,
                               sb.quantity, sb.original_quantity
                        FROM stock_batches sb
                        WHERE sb.quantity < 0
                        ORDER BY sb.branch_id, sb.item_id, sb.id
                        """);
                print(statement, "RECOMPUTED NEGATIVE_AFTER", """
                        SELECT runs.branch_id, runs.item_id, runs.negative_after AS recorded_negative_after,
                               COALESCE(SUM(CASE WHEN batches.quantity < 0
                                                 THEN -batches.quantity ELSE 0 END), 0)
                                   AS actual_negative_after
                        FROM stock_batch_reconciliation_runs runs
                        LEFT JOIN stock_batches batches
                          ON batches.branch_id = runs.branch_id
                         AND batches.item_id = runs.item_id
                        WHERE runs.migration_version = 'V66'
                        GROUP BY runs.branch_id, runs.item_id, runs.negative_after
                        ORDER BY runs.branch_id, runs.item_id
                        """);
                print(statement, "FINAL V66 SUMMARY", """
                        SELECT COUNT(*) AS affected_branch_item_ledgers,
                               COALESCE(SUM(offsets_applied), 0) AS offsets_applied,
                               COALESCE(SUM(negative_after), 0) AS unresolved_negative_quantity
                        FROM stock_batch_reconciliation_runs
                        WHERE migration_version = 'V66'
                        """);
                print(statement, "BATCH IDS AND ORIGINAL QUANTITIES", """
                        SELECT id, quantity, original_quantity FROM stock_batches ORDER BY id
                        """);
                print(statement, "HISTORICAL REFERENCES", """
                        SELECT u.id usage_id, u.batch_id, u.quantity usage_qty,
                               g.id grn_item_id, g.qty grn_qty, g.display_qty
                        FROM order_item_stock_usages u CROSS JOIN grn_items g
                        WHERE u.id = 1 AND g.id = 1
                        """);
                verifyV69ReversalUniqueness(statement);
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(server, USER, PASSWORD);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static void seed(Statement statement) throws Exception {
        statement.execute("""
                INSERT INTO branches (id, code, name, active)
                VALUES (1, 'MAIN', 'Main', 1), (2, 'OTHER', 'Other', 1)
                """);
        statement.execute("INSERT INTO categories (id, name) VALUES (1, 'Verification')");
        statement.execute("INSERT INTO sub_categories (id, name, category_id) VALUES (1, 'Verification sub', 1)");
        statement.execute("""
                INSERT INTO items (id, barcode, name, sub_category_id, cost_price, selling_price,
                    reorder_level, item_type, default_unit, active, pos_visible,
                    is_kot_enabled, stock_processing_enabled, overhead_cost_mode, overhead_cost_value, created_at)
                VALUES
                  (1, 'PHASE1-VERIFY-A', 'Phase 1 positive total', 1, 10, 20, 0, 'NORMAL', 'PCS', 1, 1, 0, 0, 'NONE', 0, NOW(6)),
                  (2, 'PHASE1-VERIFY-B', 'Phase 1 negative total', 1, 10, 20, 0, 'NORMAL', 'PCS', 1, 1, 0, 0, 'NONE', 0, NOW(6)),
                  (3, 'PHASE1-VERIFY-C', 'Phase 1 zero total', 1, 10, 20, 0, 'NORMAL', 'PCS', 1, 1, 0, 0, 'NONE', 0, NOW(6)),
                  (4, 'PHASE1-VERIFY-D', 'Phase 1 multi branch', 1, 10, 20, 0, 'NORMAL', 'PCS', 1, 1, 0, 0, 'NONE', 0, NOW(6))
                """);
        statement.execute("""
                INSERT INTO suppliers (id, name, phone, active)
                VALUES (1, 'Verification supplier', '0770000000', 1)
                """);
        // The sample ledgers mirror the requested datasets. They are independent
        // branch/item scopes so one dataset cannot alter another through V66.
        statement.execute("""
                INSERT INTO stock_batches (id, branch_id, item_id, supplier_id, batch_code,
                    source_type, cost_price, selling_price, quantity, original_quantity, received_at)
                VALUES
                  (101,1,1,1,'A-NEG','PURCHASE',10,20,-5000,5000,NOW(6)-INTERVAL 2 DAY),
                  (102,1,1,1,'A-POS','PURCHASE',10,20,10000,10000,NOW(6)-INTERVAL 1 DAY),
                  (111,1,2,1,'B-NEG','PURCHASE',10,20,-8000,8000,NOW(6)-INTERVAL 2 DAY),
                  (112,1,2,1,'B-POS','PURCHASE',10,20,3000,3000,NOW(6)-INTERVAL 1 DAY),
                  (121,1,3,1,'C-NEG','PURCHASE',10,20,-5000,5000,NOW(6)-INTERVAL 2 DAY),
                  (122,1,3,1,'C-POS','PURCHASE',10,20,5000,5000,NOW(6)-INTERVAL 1 DAY),
                  (131,1,4,1,'D-MAIN-NEG','PURCHASE',10,20,-5000,5000,NOW(6)-INTERVAL 2 DAY),
                  (132,2,4,1,'D-OTHER-POS','PURCHASE',10,20,5000,5000,NOW(6)-INTERVAL 1 DAY)
                """);
        statement.execute("INSERT INTO orders (id, invoice_no, branch_id, cashier_user_id, order_type, sale_mode, status, sub_total, bill_discount, grand_total, paid_amount, due_amount, offline_imported, promotion_discount_total, bill_promotion_discount_amount) VALUES (1, 'PHASE1-ORDER', 1, 1, 'CASH', 'TAKEAWAY', 'COMPLETED', 20, 0, 20, 20, 0, 0, 0, 0)");
        statement.execute("""
                INSERT INTO order_items (id, order_id, item_id, batch_id, barcode, item_name,
                    item_type, qty, display_qty, qty_unit, cost_price, unit_price,
                    discount_type, discount_value, final_unit_price, line_cost, line_total,
                    promotion_discount_amount)
                VALUES (1, 1, 1, 101, 'PHASE1-VERIFY', 'Phase 1 verification', 'NORMAL',
                    1000, 1, 'PCS', 10, 20, 'NONE', 0, 20, 10, 20, 0)
                """);
        statement.execute("INSERT INTO order_item_stock_usages (id, order_item_id, item_id, batch_id, quantity, restored_quantity) VALUES (1, 1, 1, 101, 1000, 0)");
        statement.execute("INSERT INTO grn (id, grn_no, supplier_id, branch_id, total_amount, received_at) VALUES (1, 'PHASE1-GRN', 1, 1, 20, NOW(6))");
        statement.execute("""
                INSERT INTO grn_items (id, grn_id, item_id, qty, display_qty, free_qty,
                    display_free_qty, qty_unit, cost_price, selling_price, amount)
                VALUES (1, 1, 1, 10000, 10, 0, 0, 'PCS', 10, 20, 100)
                """);
    }

    private static void verifyV69ReversalUniqueness(Statement statement) throws Exception {
        statement.execute("""
                INSERT INTO stock_adjustments
                    (id, branch_id, item_id, qty_change, reason, type, user_id,
                     display_qty_change, qty_unit, purchase_id, stock_batch_id)
                VALUES
                    (501, 1, 1, 5000, 'YES purchase correction', 'MANUAL', 1,
                     5, 'PCS', 9001, 101),
                    (502, 1, 1, -5000, 'Cancellation reversal', 'MANUAL', 1,
                     -5, 'PCS', 9001, 101)
                """);
        statement.execute("""
                INSERT INTO stock_adjustments
                    (id, branch_id, item_id, qty_change, reason, type, user_id,
                     display_qty_change, qty_unit, purchase_id, stock_batch_id)
                VALUES
                    (503, 1, 1, 1, 'Unrelated manual adjustment', 'MANUAL', 1,
                     1, 'PCS', NULL, 102)
                """);
        statement.execute("UPDATE stock_adjustments SET reversal_of_adjustment_id = 501 WHERE id = 502");
        statement.execute("UPDATE stock_adjustments SET reversed_by_adjustment_id = 502 WHERE id = 501");

        boolean duplicateRejected = false;
        try {
            statement.execute("""
                    INSERT INTO stock_adjustments
                        (id, branch_id, item_id, qty_change, reason, type, user_id,
                         display_qty_change, qty_unit, purchase_id, stock_batch_id,
                         reversal_of_adjustment_id)
                    VALUES
                        (504, 1, 1, -5000, 'Duplicate reversal', 'MANUAL', 1,
                         -5, 'PCS', 9001, 101, 501)
                    """);
        } catch (java.sql.SQLException expected) {
            duplicateRejected = true;
        }
        print(statement, "V69 INDEXES", """
                SELECT index_name, non_unique, column_name, seq_in_index
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'stock_adjustments'
                  AND index_name IN ('uk_stock_adjustment_reversal','idx_stock_adjustment_purchase')
                ORDER BY index_name, seq_in_index
                """);
        print(statement, "V69 REVERSAL UNIQUENESS", """
                SELECT COUNT(*) AS ordinary_nulls,
                       (SELECT COUNT(*) FROM stock_adjustments
                        WHERE reversal_of_adjustment_id = 501) AS linked_reversals
                FROM stock_adjustments
                WHERE reversal_of_adjustment_id IS NULL
                """);
        System.out.println("duplicate_reversal_rejected=" + duplicateRejected);
    }

    private static void print(Statement statement, String title, String sql) throws Exception {
        System.out.println("\n== " + title + " ==");
        try (ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            for (int i = 1; i <= columns; i++) {
                System.out.print(rows.getMetaData().getColumnLabel(i));
                System.out.print(i == columns ? "\n" : "\t");
            }
            while (rows.next()) {
                for (int i = 1; i <= columns; i++) {
                    System.out.print(rows.getString(i));
                    System.out.print(i == columns ? "\n" : "\t");
                }
            }
        }
    }
}