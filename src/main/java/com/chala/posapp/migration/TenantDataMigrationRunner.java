package com.chala.posapp.migration;

import com.chala.posapp.entity.TenantDatabase.MigrationStatus;
import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("migrate-tenants")
@RequiredArgsConstructor
public class TenantDataMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TenantDatabaseRegistry registry;
    private final TenantProvisioningService provisioningService;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Value("${tenant-migration.batch-size:10}")
    private int batchSize;

    @Value("${tenant-migration.tenants:}")
    private String tenantList;

    @Value("${tenant-migration.dry-run:true}")
    private boolean dryRun;

    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        List<String> tenants = loadTenantIds();
        log.info("Tenant migration starting. dryRun={}, batchSize={}, selectedTenants={}", dryRun, batchSize, tenants);

        int copied = 0;
        int skipped = 0;
        int failed = 0;
        for (String tenantId : tenants) {
            if (isAlreadyCopied(tenantId)) {
                skipped++;
                log.info("Skipping already copied tenant: {}", tenantId);
                continue;
            }
            if (dryRun) {
                log.info("[dry-run] Would copy tenant {} to {}", tenantId, TenantDatabaseRegistry.dbNameForTenant(tenantId));
                continue;
            }
            try {
                migrateTenant(tenantId);
                copied++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("Tenant migration failed. tenantId={}", tenantId, ex);
            }
        }

        log.info("Tenant migration finished. copied={}, skipped={}, failed={}, dryRun={}", copied, skipped, failed, dryRun);
    }

    private List<String> loadTenantIds() {
        String master = quoteIdentifier(masterDb);
        String sql = "SELECT tenant_id FROM " + master + ".tenant_subscriptions ORDER BY tenant_id";
        List<String> allTenants = jdbcTemplate.queryForList(sql, String.class);

        Set<String> selected = parseTenantList();
        return allTenants.stream()
                .filter(tenant -> selected.isEmpty() || selected.contains(tenant))
                .limit(Math.max(batchSize, 0))
                .toList();
    }

    private Set<String> parseTenantList() {
        if (tenantList == null || tenantList.isBlank()) {
            return Set.of();
        }
        Set<String> tenants = new LinkedHashSet<>();
        Arrays.stream(tenantList.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(tenants::add);
        return tenants;
    }

    private boolean isAlreadyCopied(String tenantId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM %s.tenant_databases
                WHERE tenant_id = ?
                  AND status IN ('COPIED', 'MIGRATED')
                """.formatted(quoteIdentifier(masterDb)), Integer.class, tenantId);
        return count != null && count > 0;
    }

    private void migrateTenant(String tenantId) {
        String dbName = TenantDatabaseRegistry.dbNameForTenant(tenantId);
        String targetSchema = quoteIdentifier(dbName);
        String sourceSchema = quoteIdentifier(legacyDb);

        registry.register(tenantId, dbName, MigrationStatus.PENDING);
        try {
            provisioningService.provisionSchema(dbName);

            jdbcTemplate.execute((Connection connection) -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET FOREIGN_KEY_CHECKS=0");
                    for (String tableName : loadTenantTables(dbName)) {
                        copyTable(connection, sourceSchema, targetSchema, tableName, tenantId);
                        verifyTableCount(connection, sourceSchema, targetSchema, tableName, tenantId);
                    }
                } finally {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET FOREIGN_KEY_CHECKS=1");
                    }
                }
                return null;
            });

            registry.register(tenantId, dbName, MigrationStatus.COPIED);
            log.info("Tenant data copied. tenantId={}, dbName={}", tenantId, dbName);
        } catch (RuntimeException ex) {
            registry.register(tenantId, dbName, MigrationStatus.FAILED);
            throw ex;
        }
    }

    private void copyTable(Connection connection, String sourceSchema, String targetSchema,
                           String tableName, String tenantId) {
        List<String> cols = columnCache.computeIfAbsent(tableName, this::loadColumnsExcludingTenantId);
        if (cols.isEmpty()) {
            throw new IllegalStateException("No copyable columns found for table " + tableName);
        }
        String colList = cols.stream().map(c -> "`" + c + "`").collect(Collectors.joining(","));
        String table = quoteIdentifier(tableName);
        String sql = "INSERT INTO " + targetSchema + "." + table + " (" + colList + ") "
                + "SELECT " + colList + " FROM " + sourceSchema + "." + table + " WHERE tenant_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Copy failed for table " + tableName + " tenant " + tenantId, ex);
        }
    }

    private List<String> loadColumnsExcludingTenantId(String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name <> 'tenant_id'
                ORDER BY ordinal_position
                """, String.class, legacyDb, tableName);
    }

    private List<String> loadTenantTables(String targetDbName) {
        // Tables that exist in the provisioned target schema AND have tenant_id in pos_db.
        // This excludes: ad-hoc backup tables in pos_db, and non-tenant tables like app_migrations.
        return jdbcTemplate.queryForList("""
                SELECT t.table_name
                FROM information_schema.tables t
                JOIN information_schema.columns c
                    ON c.table_schema = ?
                   AND c.table_name   = t.table_name
                   AND c.column_name  = 'tenant_id'
                WHERE t.table_schema = ?
                  AND t.table_type   = 'BASE TABLE'
                  AND t.table_name  <> 'flyway_schema_history'
                ORDER BY t.table_name
                """, String.class, legacyDb, targetDbName);
    }

    private void verifyTableCount(Connection connection, String sourceSchema, String targetSchema,
                                  String tableName, String tenantId) {
        String table = quoteIdentifier(tableName);
        Long sourceCount = countRows(connection,
                "SELECT COUNT(*) FROM " + sourceSchema + "." + table + " WHERE tenant_id = ?",
                tenantId);
        Long targetCount = countRows(connection,
                "SELECT COUNT(*) FROM " + targetSchema + "." + table,
                null);
        if (!sourceCount.equals(targetCount)) {
            throw new IllegalStateException("Count mismatch for tenant=" + tenantId
                    + ", table=" + tableName
                    + ", source=" + sourceCount
                    + ", target=" + targetCount);
        }
    }

    private Long countRows(Connection connection, String sql, String tenantId) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (tenantId != null) {
                statement.setString(1, tenantId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed count query: " + sql, ex);
        }
    }

    private String quoteIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe database identifier: " + value);
        }
        return "`" + value + "`";
    }
}
