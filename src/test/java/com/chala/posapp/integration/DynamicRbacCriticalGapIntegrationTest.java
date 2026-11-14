package com.chala.posapp.integration;

import com.chala.posapp.rbac.TenantAdminFloorService;
import com.chala.posapp.rbac.TenantRbacJdbcService;
import com.chala.posapp.rbac.TenantOwnershipException;
import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacMigrationService;
import com.chala.posapp.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class DynamicRbacCriticalGapIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.45")
            .withDatabaseName("pos_db")
            .withUsername("root")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void initializeDataSource() {
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    @BeforeEach
    void createCatalogs() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            for (String catalog : new String[]{"legacy_gap", "ready_gap"}) {
                statement.execute("DROP DATABASE IF EXISTS `" + catalog + "`");
                statement.execute("CREATE DATABASE `" + catalog + "`");
            }
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void ordinaryTenantFlywayCreatesV70SingletonWithoutRbacReadiness() throws Exception {
        migrateOrdinaryTenant("legacy_gap");

        assertTrue(tableExists("legacy_gap", "admin_floor_lock"));
        assertEquals(1, scalarInt("legacy_gap", "SELECT COUNT(*) FROM admin_floor_lock WHERE id = 1"));
        assertEquals("InnoDB", scalar("legacy_gap", "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'legacy_gap' AND TABLE_NAME = 'admin_floor_lock'"));
        assertFalse(tableExists("legacy_gap", "rbac_flyway_schema_history"));
        assertFalse(tableExists("legacy_gap", "tenant_ownership"));
    }

    @Test
    void legacyStatusFailsClosedWhenLockTableOrRowIsMissing() throws Exception {
        createLegacyUsers("legacy_gap");
        TenantRbacJdbcService service = legacyJdbcService();

        RuntimeException missingTable = assertThrows(RuntimeException.class,
                () -> TenantContext.callWith("LEGACY", () -> {
                    service.updateLegacyUserStatus(1L, false);
                    return null;
                }));
        assertNotNull(missingTable);
        assertEquals(1, scalarInt("legacy_gap", "SELECT enabled FROM users WHERE id = 1"));

        try (Connection connection = connect("legacy_gap"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE admin_floor_lock (id TINYINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        }
        RuntimeException missingRow = assertThrows(RuntimeException.class,
                () -> TenantContext.callWith("LEGACY", () -> {
                    service.updateLegacyUserStatus(1L, false);
                    return null;
                }));
        assertNotNull(missingRow);
        assertEquals(1, scalarInt("legacy_gap", "SELECT enabled FROM users WHERE id = 1"));
    }

    @Test
    void readyTenantCreationCommitsUserAndRbacFieldsTogether() throws Exception {
        createReadyUsersAndRoles("ready_gap");
        TenantRbacJdbcService service = readyJdbcService();

        TenantRbacJdbcService.TenantUserCreateResult result = TenantContext.callWith("READY", () ->
                service.createReadyTenantUser("created", "hash", "CASHIER", true, null));

        assertNotNull(result.userId());
        assertEquals(7L, result.roleId());
        assertEquals(1L, result.rbacVersion());
        assertEquals(1, scalarInt("ready_gap", "SELECT COUNT(*) FROM users WHERE id = " + result.userId()));
        assertEquals(7, scalarInt("ready_gap", "SELECT role_id FROM users WHERE id = " + result.userId()));
        assertEquals(1, scalarInt("ready_gap", "SELECT rbac_version FROM users WHERE id = " + result.userId()));
    }

    @Test
    void readyTenantCreationRollsBackWhenSystemRoleResolutionFails() throws Exception {
        createReadyUsersAndRoles("ready_gap");
        TenantRbacJdbcService service = readyJdbcService();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> TenantContext.callWith("READY", () ->
                service.createReadyTenantUser("will-rollback", "hash", "MANAGER", true, null)));

        assertNotNull(failure);
        assertEquals(0, scalarInt("ready_gap", "SELECT COUNT(*) FROM users WHERE username = 'will-rollback'"));
    }

    @Test
    void ownershipRowLockSerializesConcurrentReadyTenantCallbacks() throws Exception {
        try (Connection connection = connect("ready_gap"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tenant_ownership (id BIGINT NOT NULL PRIMARY KEY, singleton_key VARCHAR(20) NOT NULL UNIQUE) ENGINE=InnoDB");
            statement.execute("INSERT INTO tenant_ownership (id, singleton_key) VALUES (1, 'TENANT')");
        }
        TenantRbacJdbcService service = readyJdbcService();
        TenantAdminFloorService floor = new TenantAdminFloorService(service);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> TenantContext.callWith("READY", () -> {
                floor.withAdminFloorLock(jdbc -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                    return null;
                });
                return null;
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> TenantContext.callWith("READY", () -> {
                floor.withAdminFloorLock(jdbc -> {
                    secondEntered.countDown();
                    return null;
                });
                return null;
            }));
            assertFalse(secondEntered.await(300, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static TenantRbacJdbcService legacyJdbcService() {
        TenantDatabaseRegistry registry = mock(TenantDatabaseRegistry.class);
        when(registry.resolveCatalog("LEGACY")).thenReturn("legacy_gap");
        TenantRbacMigrationService migration = mock(TenantRbacMigrationService.class);
        TenantRbacJdbcService service = new TenantRbacJdbcService(dataSource, registry, migration);
        ReflectionTestUtils.setField(service, "legacyDb", "legacy_gap");
        ReflectionTestUtils.setField(service, "masterDb", "pos_master");
        return service;
    }

    private static TenantRbacJdbcService readyJdbcService() {
        TenantDatabaseRegistry registry = mock(TenantDatabaseRegistry.class);
        when(registry.resolveCatalog("READY")).thenReturn("ready_gap");
        TenantRbacMigrationService migration = mock(TenantRbacMigrationService.class);
        when(migration.isReady("ready_gap")).thenReturn(true);
        TenantRbacJdbcService service = new TenantRbacJdbcService(dataSource, registry, migration);
        ReflectionTestUtils.setField(service, "legacyDb", "legacy_gap");
        ReflectionTestUtils.setField(service, "masterDb", "pos_master");
        return service;
    }

    private static void migrateOrdinaryTenant(String catalog) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(catalog)
                .defaultSchema(catalog)
                .locations("classpath:db/migration/tenant")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .mixed(true)
                .load()
                .migrate();
    }

    private static void createLegacyUsers(String catalog) throws Exception {
        try (Connection connection = connect(catalog); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT NOT NULL PRIMARY KEY, role ENUM('ADMIN','CASHIER','MANAGER','SUPER_ADMIN') NOT NULL, enabled BIT(1) NOT NULL, username VARCHAR(80) NOT NULL, password_hash VARCHAR(255) NOT NULL) ENGINE=InnoDB");
            statement.execute("INSERT INTO users (id, role, enabled, username, password_hash) VALUES (1, 'ADMIN', 1, 'admin', 'hash')");
        }
    }

    private static void createReadyUsersAndRoles(String catalog) throws Exception {
        try (Connection connection = connect(catalog); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE roles (id BIGINT NOT NULL PRIMARY KEY, legacy_compatibility VARCHAR(30), is_system BIT(1) NOT NULL, is_active BIT(1) NOT NULL) ENGINE=InnoDB");
            statement.execute("CREATE TABLE users (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, role ENUM('ADMIN','CASHIER','MANAGER','SUPER_ADMIN','CUSTOM') NOT NULL, username VARCHAR(80) NOT NULL UNIQUE, enabled BIT(1) NOT NULL, password_hash VARCHAR(255) NOT NULL, branch_id BIGINT NULL, created_at DATETIME(6) NULL, role_id BIGINT NULL, rbac_version BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB");
            statement.execute("INSERT INTO roles (id, legacy_compatibility, is_system, is_active) VALUES (7, 'CASHIER', 1, 1)");
        }
    }

    private static Connection connect(String catalog) throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl().replace("pos_db", catalog), mysql.getUsername(), mysql.getPassword());
    }

    private static String serverUrl() {
        return mysql.getJdbcUrl().replace("/pos_db", "/");
    }

    private static boolean tableExists(String catalog, String table) throws Exception {
        return scalarInt(catalog, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + catalog + "' AND TABLE_NAME = '" + table + "'") == 1;
    }

    private static int scalarInt(String catalog, String sql) throws Exception {
        try (Connection connection = connect(catalog); Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String scalar(String catalog, String sql) throws Exception {
        try (Connection connection = connect(catalog); Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test coordination");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
