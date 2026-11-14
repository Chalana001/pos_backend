package com.chala.posapp.integration;

import com.chala.posapp.service.TenantRbacBaselineService;
import com.chala.posapp.service.TenantRbacMigrationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class TenantRbacMigrationMysqlIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.45")
            .withDatabaseName("pos_db")
            .withUsername("root")
            .withPassword("test");

    private static TenantRbacMigrationService service;
    private static TenantRbacBaselineService baselineService;

    @BeforeAll
    static void setUpCatalogs() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS pos_master");
            statement.execute("CREATE DATABASE IF NOT EXISTS tenant_a_db");
            statement.execute("CREATE DATABASE IF NOT EXISTS tenant_b_db");
            createUsersTable(statement, "tenant_a_db");
            createUsersTable(statement, "tenant_b_db");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                serverUrl(), mysql.getUsername(), mysql.getPassword());
        service = new TenantRbacMigrationService(dataSource, "pos_db", "pos_master");
        baselineService = new TenantRbacBaselineService(dataSource, "pos_db", "pos_master");
    }

    @BeforeEach
    void resetTenantCatalogs() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            for (String catalog : List.of("tenant_a_db", "tenant_b_db")) {
                statement.execute("DROP DATABASE IF EXISTS `" + catalog + "`");
                statement.execute("CREATE DATABASE `" + catalog + "`");
                createUsersTable(statement, catalog);
            }
        }
    }

    @Test
    void migratesOnlySelectedTenantCatalogAndLeavesSharedAndOtherCatalogsUntouched() throws Exception {
        assertDoesNotThrow(() -> service.migrateAndVerify("tenant_a_db"));

        assertTrue(tableExists("tenant_a_db", "roles"));
        assertTrue(tableExists("tenant_a_db", "role_permissions"));
        assertTrue(tableExists("tenant_a_db", "user_permission_overrides"));
        assertTrue(tableExists("tenant_a_db", "rbac_change_audit"));
        assertTrue(tableExists("tenant_a_db", "rbac_flyway_schema_history"));
        assertTrue(columnExists("tenant_a_db", "users", "role_id"));
        assertTrue(columnExists("tenant_a_db", "users", "rbac_version"));

        assertFalse(tableExists("pos_db", "roles"));
        assertFalse(tableExists("pos_db", "rbac_flyway_schema_history"));
        assertFalse(tableExists("pos_master", "roles"));
        assertFalse(tableExists("pos_master", "rbac_flyway_schema_history"));
        assertFalse(tableExists("tenant_b_db", "roles"));
        assertFalse(tableExists("tenant_b_db", "rbac_flyway_schema_history"));

        assertDoesNotThrow(() -> service.migrateAndVerify("tenant_b_db"));
        assertTrue(service.isReady("tenant_a_db"));
        assertTrue(service.isReady("tenant_b_db"));

        try (Connection connection = DriverManager.getConnection(serverUrl(), mysql.getUsername(), mysql.getPassword())) {
            assertTrue(connection.getCatalog() == null || connection.getCatalog().isBlank(),
                    "shared datasource connection must not retain a tenant catalog");
        }
    }

    @Test
    void rerunningSuccessfulMigrationIsSafeAndHistoryHasOneVersion() throws Exception {
        assertDoesNotThrow(() -> service.migrateAndVerify("tenant_a_db"));
        assertDoesNotThrow(() -> service.migrateAndVerify("tenant_a_db"));

        try (Connection connection = connect("tenant_a_db");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM rbac_flyway_schema_history WHERE version = '1' AND success = 1")) {
            assertTrue(result.next());
            assertTrue(result.getInt(1) == 1);
        }
    }

    @Test
    void concurrentCopiedPromotionAllowsExactlyOneWinner() throws Exception {
        try (Connection connection = connect("pos_master");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS tenant_databases ("
                    + "tenant_id VARCHAR(100) PRIMARY KEY, db_name VARCHAR(120) NOT NULL, "
                    + "status ENUM('PENDING','COPIED','MIGRATED','FAILED') NOT NULL, "
                    + "created_at DATETIME(6), migrated_at DATETIME(6)) ENGINE=InnoDB");
            statement.execute("DELETE FROM tenant_databases WHERE tenant_id = 'race-tenant'");
            statement.execute("INSERT INTO tenant_databases (tenant_id, db_name, status, created_at) "
                    + "VALUES ('race-tenant', 'tenant_a_db', 'COPIED', CURRENT_TIMESTAMP(6))");
        }

        var first = new com.chala.posapp.service.TenantDatabaseRegistry(
                new org.springframework.jdbc.core.JdbcTemplate(
                        new DriverManagerDataSource(serverUrl(), mysql.getUsername(), mysql.getPassword())));
        var second = new com.chala.posapp.service.TenantDatabaseRegistry(
                new org.springframework.jdbc.core.JdbcTemplate(
                        new DriverManagerDataSource(serverUrl(), mysql.getUsername(), mysql.getPassword())));
        ReflectionTestUtils.setField(first, "masterDb", "pos_master");
        ReflectionTestUtils.setField(second, "masterDb", "pos_master");
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> firstResult = pool.submit(() -> promote(start, first));
        Future<Boolean> secondResult = pool.submit(() -> promote(start, second));
        start.countDown();

        int winners = (firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0);
        pool.shutdownNow();
        assertTrue(winners == 1, "exactly one concurrent promotion must win");

        try (Connection connection = connect("pos_master");
             var prepared = connection.prepareStatement(
                     "SELECT status, db_name FROM tenant_databases WHERE tenant_id = 'race-tenant'");
             ResultSet result = prepared.executeQuery()) {
            assertTrue(result.next());
            assertTrue("MIGRATED".equals(result.getString("status")));
            assertTrue("tenant_a_db".equals(result.getString("db_name")));
        }

        assertThrows(IllegalStateException.class,
                () -> first.promoteCopiedToMigrated("race-tenant", "tenant_a_db"));
    }

    private static boolean promote(CountDownLatch start,
                                   com.chala.posapp.service.TenantDatabaseRegistry registry)
            throws InterruptedException {
        start.await();
        try {
            registry.promoteCopiedToMigrated("race-tenant", "tenant_a_db");
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    @Test
    void seedsBothTenantsBackfillsLegacyUsersAndLeavesMasterAndLegacyUntouched() throws Exception {
        service.migrateAndVerify("tenant_a_db");
        service.migrateAndVerify("tenant_b_db");
        insertUsers("tenant_a_db");
        insertUsers("tenant_b_db");

        var first = baselineService.seedAndBackfill("tenant_a_db");
        var second = baselineService.seedAndBackfill("tenant_b_db");

        assertTrue(first.createdRoles() == 3);
        assertTrue(second.createdRoles() == 3);
        assertEquals(3, count("tenant_a_db", "roles"));
        assertEquals(3, count("tenant_b_db", "roles"));
        assertEquals("1", scalar("tenant_a_db", "SELECT is_protected FROM roles WHERE role_key = 'DEFAULT_ADMIN'"));
        assertEquals(3, count("tenant_a_db", "users", "role_id IS NOT NULL"));
        assertEquals(3, count("tenant_b_db", "users", "role_id IS NOT NULL"));
        assertEquals("ADMIN", scalar("tenant_a_db", "SELECT role FROM users WHERE username = 'a-admin'"));
        assertEquals("CASHIER", scalar("tenant_b_db", "SELECT role FROM users WHERE username = 'b-cashier'"));
        assertFalse(tableExists("pos_db", "roles"));
        assertFalse(tableExists("pos_master", "roles"));
    }

    @Test
    void rerunningBaselineIsIdempotentAndPreservesExistingRoleAssociation() throws Exception {
        service.migrateAndVerify("tenant_a_db");
        insertUsers("tenant_a_db");
        var first = baselineService.seedAndBackfill("tenant_a_db");
        int roleCount = count("tenant_a_db", "roles");
        int grantCount = count("tenant_a_db", "role_permissions");
        var second = baselineService.seedAndBackfill("tenant_a_db");

        assertEquals(3, first.createdRoles());
        assertEquals(0, second.createdRoles());
        assertEquals(3, second.preservedRoles());
        assertEquals(0, second.backfilledUsers());
        assertEquals(roleCount, count("tenant_a_db", "roles"));
        assertEquals(grantCount, count("tenant_a_db", "role_permissions"));
    }

    @Test
    void superAdminUserIsReportedWithoutTenantRoleAssignment() throws Exception {
        service.migrateAndVerify("tenant_a_db");
        insertUsers("tenant_a_db");
        try (Connection connection = connect("tenant_a_db");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO users (role, username, enabled, password_hash) VALUES "
                    + "('SUPER_ADMIN', 'a-super', 1, 'x')");
        }

        var result = baselineService.seedAndBackfill("tenant_a_db");

        assertEquals(1, result.unmappedUsers().size());
        assertEquals(0, scalarInt("tenant_a_db", "SELECT COUNT(*) FROM users WHERE username = 'a-super' AND role_id IS NOT NULL"));
    }

    @Test
    void reservedCatalogsAreRejectedBeforeAnyBaselineWrite() {
        for (String catalog : List.of("pos_db", "pos_master", "", "tenant-a-db", "tenant_a_db;DROP TABLE users")) {
            assertThrows(IllegalArgumentException.class, () -> baselineService.seedAndBackfill(catalog));
        }
    }

    @Test
    void rejectsReservedAndUnsafeCatalogsBeforeMigration() { 
        for (String catalog : List.of("pos_db", "pos_master", "", "tenant-a-db", "tenant_a_db;DROP TABLE users")) {
            assertThrows(TenantRbacMigrationService.RbacMigrationException.class,
                    () -> service.migrateAndVerify(catalog));
        }
    }

    @Test
    void incompatiblePreExistingObjectFailsClearlyAndDoesNotPreventAnotherTenant() throws Exception {
        try (Connection connection = connect("tenant_b_db");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS roles");
            statement.execute("CREATE TABLE roles (id INT PRIMARY KEY)");
            statement.execute("DROP TABLE IF EXISTS rbac_flyway_schema_history");
        }

        assertThrows(TenantRbacMigrationService.RbacMigrationException.class,
                () -> service.migrateAndVerify("tenant_b_db"));
        assertDoesNotThrow(() -> service.migrateAndVerify("tenant_a_db"));
        assertFalse(service.isReady("tenant_b_db"));
        assertTrue(service.isReady("tenant_a_db"));
    }

    private static void createUsersTable(Statement statement, String catalog) throws Exception {
        statement.execute("CREATE TABLE IF NOT EXISTS `" + catalog + "`.users ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "role ENUM('ADMIN','CASHIER','MANAGER','SUPER_ADMIN') NOT NULL, "
                + "username VARCHAR(80) NOT NULL UNIQUE, "
                + "enabled BIT(1) NOT NULL, password_hash VARCHAR(255) NOT NULL"
                + ") ENGINE=InnoDB");
    }

    private static void insertUsers(String catalog) throws Exception {
        try (Connection connection = connect(catalog); Statement statement = connection.createStatement()) {
            String prefix = catalog.equals("tenant_a_db") ? "a" : "b";
            statement.execute("INSERT INTO users (role, username, enabled, password_hash) VALUES "
                    + "('ADMIN', '" + prefix + "-admin', 1, 'x'),"
                    + "('MANAGER', '" + prefix + "-manager', 1, 'x'),"
                    + "('CASHIER', '" + prefix + "-cashier', 1, 'x')");
        }
    }

    private static int count(String catalog, String table) throws Exception {
        return scalarInt(catalog, "SELECT COUNT(*) FROM `" + table + "`");
    }

    private static int count(String catalog, String table, String predicate) throws Exception {
        return scalarInt(catalog, "SELECT COUNT(*) FROM `" + table + "` WHERE " + predicate);
    }

    private static int scalarInt(String catalog, String sql) throws Exception {
        return Integer.parseInt(scalar(catalog, sql));
    }

    private static String scalar(String catalog, String sql) throws Exception {
        try (Connection connection = connect(catalog);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("No scalar result for " + sql);
            return result.getString(1);
        }
    }

    private static String serverUrl() {
        return mysql.getJdbcUrl().replaceFirst("/pos_db(?:\\?.*)?$", "/");
    }

    private static Connection connect(String catalog) throws Exception {
        return DriverManager.getConnection(serverUrl() + catalog, mysql.getUsername(), mysql.getPassword());
    }

    private static boolean tableExists(String catalog, String table) throws Exception {
        try (Connection connection = connect(catalog);
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM information_schema.tables
                     WHERE table_schema = ? AND table_name = ?
                     """)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(String catalog, String table, String column) throws Exception {
        try (Connection connection = connect(catalog);
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ? AND column_name = ?
                     """)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
