package com.chala.posapp.service;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class TenantRbacMigrationService {

    public static final String RBAC_MIGRATION_LOCATION = "classpath:db/migration/tenant-rbac";
    public static final String RBAC_HISTORY_TABLE = "rbac_flyway_schema_history";
    public static final String RBAC_MIGRATION_VERSION = "3";

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "roles", "role_permissions", "user_permission_overrides", "rbac_change_audit", "tenant_ownership", "tenant_reauth_challenges"
    );
    private static final Set<String> REQUIRED_USER_COLUMNS = Set.of("role_id", "rbac_version");

    private final DataSource dataSource;
    private final String legacyDb;
    private final String masterDb;

    public TenantRbacMigrationService(
            DataSource dataSource,
            @Value("${app.datasource.legacy-db:pos_db}") String legacyDb,
            @Value("${app.datasource.master-db:pos_master}") String masterDb) {
        this.dataSource = dataSource;
        this.legacyDb = legacyDb;
        this.masterDb = masterDb;
    }

    public synchronized RbacMigrationResult migrateAndVerify(String catalog) {
        validateCatalog(catalog);

        try (Connection connection = dataSource.getConnection()) {
            String originalCatalog = connection.getCatalog();
            try {
                selectAndVerifyCatalog(connection, catalog);
                preflight(connection, catalog);

                SingleConnectionDataSource flywayDataSource =
                        new SingleConnectionDataSource(connection, true);
                Flyway flyway = Flyway.configure()
                        .dataSource(flywayDataSource)
                        .schemas(catalog)
                        .defaultSchema(catalog)
                        .table(RBAC_HISTORY_TABLE)
                        .locations(RBAC_MIGRATION_LOCATION)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .cleanDisabled(true)
                        .mixed(true)
                        .load();

                flyway.migrate();
                selectAndVerifyCatalog(connection, catalog);
                verifyReady(connection, catalog);
                log.info("Tenant RBAC schema ready. catalog={}", catalog);
                return new RbacMigrationResult(catalog, true);
            } finally {
                restoreCatalog(connection, originalCatalog);
            }
        } catch (RbacMigrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RbacMigrationException("RBAC migration failed for catalog '" + catalog + "'", ex);
        }
    }

    public boolean isReady(String catalog) {
        validateCatalog(catalog);
        try (Connection connection = dataSource.getConnection()) {
            String originalCatalog = connection.getCatalog();
            try {
                selectAndVerifyCatalog(connection, catalog);
                verifyReady(connection, catalog);
                return true;
            } finally {
                restoreCatalog(connection, originalCatalog);
            }
        } catch (Exception ex) {
            return false;
        }
    }

    public void requireReady(String catalog) {
        if (!isReady(catalog)) {
            throw new RbacMigrationException("Tenant RBAC schema is not ready for catalog '" + catalog + "'");
        }
    }

    public void validateCatalog(String catalog) {
        if (catalog == null || !catalog.matches("[A-Za-z0-9_]+")) {
            throw new RbacMigrationException("Invalid isolated tenant catalog: " + catalog);
        }
        if (catalog.equalsIgnoreCase(legacyDb) || catalog.equalsIgnoreCase(masterDb)
                || catalog.equalsIgnoreCase("pos_db") || catalog.equalsIgnoreCase("pos_master")) {
            throw new RbacMigrationException("Reserved catalog cannot receive tenant RBAC migrations: " + catalog);
        }
    }

    private void selectAndVerifyCatalog(Connection connection, String catalog) throws SQLException {
        connection.setCatalog(catalog);
        if (!catalog.equalsIgnoreCase(connection.getCatalog())) {
            throw new RbacMigrationException("JDBC catalog selection mismatch for '" + catalog
                    + "': actual='" + connection.getCatalog() + "'");
        }
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !catalog.equalsIgnoreCase(result.getString(1))) {
                throw new RbacMigrationException("Database catalog selection mismatch for '" + catalog + "'");
            }
        }
    }

    private void preflight(Connection connection, String catalog) throws SQLException {
        boolean historyExists = tableExists(connection, catalog, RBAC_HISTORY_TABLE);
        boolean routineExists = routineExists(connection, catalog, "_rbac_add_user_columns");
        boolean rbacObjectExists = REQUIRED_TABLES.stream().anyMatch(table -> tableExistsUnchecked(connection, catalog, table))
                || REQUIRED_USER_COLUMNS.stream().anyMatch(column -> columnExistsUnchecked(connection, catalog, "users", column));

        if (historyExists) {
            List<HistoryState> states = readHistory(connection, catalog);
            if (states.stream().anyMatch(state -> !state.success())) {
                throw new RbacMigrationException("RBAC Flyway history contains a failed migration in " + catalog
                        + "; deliberate recovery is required");
            }
            if (states.stream().anyMatch(state -> RBAC_MIGRATION_VERSION.equals(state.version()))) {
                verifyReady(connection, catalog);
                return;
            }
            // A successful older RBAC history is migratable. Flyway must be allowed to apply
            // the next version; only failed history and partial/untracked objects fail closed.
            return;
        }

        if (routineExists || rbacObjectExists) {
            throw new RbacMigrationException("Untracked or partial RBAC schema exists in " + catalog
                    + "; inspect and recover deliberately before migration");
        }
    }

    private void verifyReady(Connection connection, String catalog) throws SQLException {
        if (!tableExists(connection, catalog, RBAC_HISTORY_TABLE)) {
            throw new RbacMigrationException("RBAC Flyway history table is missing in " + catalog);
        }
        List<HistoryState> states = readHistory(connection, catalog);
        HistoryState state = states.stream()
                .filter(value -> RBAC_MIGRATION_VERSION.equals(value.version()))
                .findFirst()
                .orElseThrow(() -> new RbacMigrationException("RBAC migration version "
                        + RBAC_MIGRATION_VERSION + " is missing in " + catalog));
        if (!state.success()) {
            throw new RbacMigrationException("RBAC migration version " + RBAC_MIGRATION_VERSION
                    + " failed in " + catalog);
        }

        for (String table : REQUIRED_TABLES) {
            if (!tableExists(connection, catalog, table)) {
                throw new RbacMigrationException("RBAC table '" + table + "' is missing in " + catalog);
            }
        }
        for (String column : REQUIRED_USER_COLUMNS) {
            if (!columnExists(connection, catalog, "users", column)) {
                throw new RbacMigrationException("RBAC users column '" + column + "' is missing in " + catalog);
            }
        }
        verifyRequiredColumns(connection, catalog);
    }

    private void verifyRequiredColumns(Connection connection, String catalog) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("roles.role_key", "varchar"),
                Map.entry("roles.is_system", "bit"),
                Map.entry("roles.is_protected", "bit"),
                Map.entry("roles.is_active", "bit"),
                Map.entry("roles.role_type", "enum"),
                Map.entry("roles.template_role_id", "bigint"),
                Map.entry("roles.legacy_compatibility", "varchar"),
                Map.entry("tenant_ownership.singleton_key", "varchar"),
                Map.entry("tenant_ownership.owner_user_id", "bigint"),
                Map.entry("tenant_ownership.status", "enum"),
                Map.entry("tenant_ownership.version", "bigint"),
                Map.entry("tenant_reauth_challenges.challenge_digest", "char"),
                Map.entry("tenant_reauth_challenges.expires_at", "datetime"),
                Map.entry("role_permissions.permission_key", "varchar"),
                Map.entry("user_permission_overrides.override_state", "enum"),
                Map.entry("rbac_change_audit.action", "varchar"),
                Map.entry("rbac_change_audit.before_state", "json"),
                Map.entry("rbac_change_audit.after_state", "json")
        );
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            String actual = columnType(connection, catalog, parts[0], parts[1]);
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                throw new RbacMigrationException("Incompatible RBAC column " + entry.getKey()
                        + " in " + catalog + ": expected " + entry.getValue() + ", actual " + actual);
            }
        }
    }

    private List<HistoryState> readHistory(Connection connection, String catalog) throws SQLException {
        String sql = "SELECT version, success FROM " + qualified(catalog, RBAC_HISTORY_TABLE);
        List<HistoryState> states = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                states.add(new HistoryState(result.getString("version"), result.getBoolean("success")));
            }
        }
        return states;
    }

    private String columnType(Connection connection, String catalog, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private boolean tableExists(Connection connection, String catalog, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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

    private boolean tableExistsUnchecked(Connection connection, String catalog, String table) {
        try {
            return tableExists(connection, catalog, table);
        } catch (SQLException ex) {
            throw new RbacMigrationException("Could not inspect catalog " + catalog, ex);
        }
    }

    private boolean columnExists(Connection connection, String catalog, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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

    private boolean columnExistsUnchecked(Connection connection, String catalog, String table, String column) {
        try {
            return columnExists(connection, catalog, table, column);
        } catch (SQLException ex) {
            throw new RbacMigrationException("Could not inspect catalog " + catalog, ex);
        }
    }

    private boolean routineExists(Connection connection, String catalog, String routine) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM information_schema.routines
                WHERE routine_schema = ? AND routine_name = ?
                """)) {
            statement.setString(1, catalog);
            statement.setString(2, routine);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String qualified(String catalog, String object) {
        return "`" + catalog + "`.`" + object + "`";
    }

    private void restoreCatalog(Connection connection, String originalCatalog) {
        try {
            String restore = originalCatalog == null || originalCatalog.isBlank() ? legacyDb : originalCatalog;
            connection.setCatalog(restore);
        } catch (Exception ex) {
            log.warn("Could not restore datasource catalog after RBAC migration: {}", ex.getMessage());
        }
    }

    public record RbacMigrationResult(String catalog, boolean ready) {
    }

    private record HistoryState(String version, boolean success) {
    }

    public static class RbacMigrationException extends RuntimeException {
        public RbacMigrationException(String message) {
            super(message);
        }

        public RbacMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
