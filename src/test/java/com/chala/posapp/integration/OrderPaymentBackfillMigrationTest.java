package com.chala.posapp.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class OrderPaymentBackfillMigrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pos_db")
            .withUsername("posapp")
            .withPassword("posapp");

    @Test
    void backfillsCompletedOrdersOnceAndPreservesLiveRows() throws Exception {
        String url = mysql.getJdbcUrl();
        migrate(url, "58");
        seedLegacyOrders(url);
        migrate(url, "59");
        // Second run must be a no-op: the legacy_backfilled flag is the idempotence guard.
        migrate(url, "59");

        try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            assertBackfillRow(statement, "LEGACY-CASH", 80, 20, "CASH", true, null);
            assertBackfillRow(statement, "LEGACY-BANK", 100, 0, "BANK", true, null);
            assertBackfillRow(statement, "LEGACY-CHEQUE", 100, 0, "CHEQUE", true, null);
            assertBackfillRow(statement, "LEGACY-NO-INITIAL-FACT", 0, 100, "CASH", true, null);
            assertBackfillRow(statement, "LEGACY-MUTATED", 30, 70, "CASH", true, null);

            try (ResultSet canceled = statement.executeQuery("""
                    SELECT COUNT(*) FROM order_payments WHERE invoice_no = 'LEGACY-CANCELED'
                    """)) {
                assertTrue(canceled.next());
                assertEquals(0, canceled.getInt(1));
            }

            try (ResultSet live = statement.executeQuery("""
                    SELECT amount, payment_method, bank_reference, legacy_backfilled, note
                    FROM order_payments WHERE invoice_no = 'LIVE-ALREADY-PAID'
                    """)) {
                assertTrue(live.next());
                assertEquals(100.0, live.getDouble("amount"), 0.001);
                assertEquals("CASH", live.getString("payment_method"));
                assertEquals("Live note", live.getString("note"));
                assertFalse(live.getBoolean("legacy_backfilled"));
            }

            try (ResultSet total = statement.executeQuery("""
                    SELECT COUNT(*) AS total,
                           SUM(legacy_backfilled = 1) AS backfilled
                    FROM order_payments
                    """)) {
                assertTrue(total.next());
                assertEquals(8, total.getInt("total"));
                assertEquals(7, total.getInt("backfilled"));
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

    private static void seedLegacyOrders(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO branches (code, name, address, phone, active)
                    VALUES ('BACKFILL', 'Backfill branch', 'Colombo', '0110000000', 1)
                    """);
            long branchId = lastInsertId(statement);
            statement.execute("""
                    INSERT INTO users (branch_id, username, password_hash, role, enabled, created_at)
                    VALUES (%d, 'backfill-cashier', 'x', 'CASHIER', 1, NOW(6))
                    """.formatted(branchId));
            long cashierId = lastInsertId(statement);

            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-CASH", 100, 80, 20, 80d, 20d, "CASH", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-BANK", 100, 100, 0, 100d, 0d, "BANK", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-CHEQUE", 100, 100, 0, 100d, 0d, "CHEQUE", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-NO-INITIAL-FACT", 100, 100, 0, null, null, "CASH", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-MUTATED", 100, 30, 70, 30d, 70d, "CASH", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-CREDIT", 100, 0, 100, 0d, 100d, "CREDIT", "COMPLETED", null);
            insertLegacyOrder(statement, branchId, cashierId, "LEGACY-CANCELED", 100, 0, 100, 0d, 100d, "CASH", "CANCELED", null);
            // The live row needs its order to exist first: the payment's order_id is looked
            // up by invoice number, and before this reorder the order did not exist yet, so
            // the subquery answered NULL and MySQL rejected the insert.
            insertLegacyOrder(statement, branchId, cashierId, "LIVE-ALREADY-PAID", 100, 100, 0, 100d, 0d, "CASH", "COMPLETED", null);
            insertOrderPayment(statement, branchId, cashierId, "LIVE-ALREADY-PAID");
        }
    }

    private static void insertLegacyOrder(
            Statement statement,
            long branchId,
            long cashierId,
            String invoiceNo,
            double grandTotal,
            double paidAmount,
            double dueAmount,
            Double salePaidAmount,
            Double saleDueAmount,
            String paymentMethod,
            String status,
            LocalDateTime createdAt
    ) throws Exception {
        try (PreparedStatement order = statement.getConnection().prepareStatement("""
                INSERT INTO orders (
                    bill_discount, branch_id, cashier_user_id, created_at, due_amount,
                    grand_total, invoice_no, order_type, paid_amount, status, sub_total,
                    sale_mode, offline_imported, payment_method, sale_due_amount,
                    sale_paid_amount, bill_promotion_discount_amount,
                    promotion_discount_total
                ) VALUES (0, ?, ?, ?, ?, ?, ?, 'CASH', ?, ?, ?, 'TAKEAWAY', 0, ?, ?, ?, 0, 0)
                """)) {
            order.setLong(1, branchId);
            order.setLong(2, cashierId);
            order.setTimestamp(3, createdAt == null ? null : java.sql.Timestamp.valueOf(createdAt));
            order.setDouble(4, dueAmount);
            order.setDouble(5, grandTotal);
            order.setString(6, invoiceNo);
            order.setDouble(7, paidAmount);
            order.setString(8, status);
            order.setDouble(9, grandTotal);
            order.setString(10, paymentMethod);
            if (saleDueAmount == null) {
                order.setNull(11, java.sql.Types.DOUBLE);
            } else {
                order.setDouble(11, saleDueAmount);
            }
            if (salePaidAmount == null) {
                order.setNull(12, java.sql.Types.DOUBLE);
            } else {
                order.setDouble(12, salePaidAmount);
            }
            order.executeUpdate();
        }
    }

    private static void insertOrderPayment(
            Statement statement,
            long branchId,
            long cashierId,
            String invoiceNo
    ) throws Exception {
        try (PreparedStatement payment = statement.getConnection().prepareStatement("""
                INSERT INTO order_payments (
                    order_id, invoice_no, branch_id, cashier_user_id, amount,
                    payment_method, bank_reference, note, paid_at, created_at
                ) VALUES ((SELECT id FROM orders WHERE invoice_no = ?), ?, ?, ?, 100,
                          'CASH', NULL, 'Live note', NOW(6), NOW(6))
                """)) {
            payment.setString(1, invoiceNo);
            payment.setString(2, invoiceNo);
            payment.setLong(3, branchId);
            payment.setLong(4, cashierId);
            payment.executeUpdate();
        }
    }

    private static void assertBackfillRow(
            Statement statement,
            String invoiceNo,
            double expectedAmount,
            double expectedShortfall,
            String expectedMethod,
            boolean expectedBackfilled,
            String expectedBankReference
    ) throws Exception {
        try (ResultSet row = statement.executeQuery("""
                SELECT amount, shortfall_amount, payment_method, bank_reference,
                       legacy_backfilled, note, tendered_amount, change_amount
                FROM order_payments WHERE invoice_no = '""" + invoiceNo + "'")) {
            assertTrue(row.next(), invoiceNo + " should be backfilled");
            assertEquals(expectedAmount, row.getDouble("amount"), 0.001);
            assertEquals(expectedShortfall, row.getDouble("shortfall_amount"), 0.001);
            assertEquals(expectedMethod, row.getString("payment_method"));
            assertEquals(expectedBankReference, row.getString("bank_reference"));
            assertEquals(expectedBackfilled, row.getBoolean("legacy_backfilled"));
            assertTrue(row.getString("note").startsWith("Legacy order payment backfill"));
            assertEquals(0.0, row.getObject("tendered_amount") == null ? 0.0 : row.getDouble("tendered_amount"), 0.001);
            assertEquals(0.0, row.getObject("change_amount") == null ? 0.0 : row.getDouble("change_amount"), 0.001);
            assertFalse(row.next());
        }
    }

    private static long lastInsertId(Statement statement) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
