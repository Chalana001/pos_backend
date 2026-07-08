package com.chala.posapp.service;

import com.chala.posapp.entity.TenantDatabase;
import com.chala.posapp.entity.TenantDatabase.MigrationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDatabaseRegistry {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Value("${app.tenant.allow-legacy-fallback:false}")
    private boolean allowLegacyFallback;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Resolve the physical catalog for a given tenantId.
     * - "MASTER" → pos_master (configured in property, but resolved in provider)
     * - migrated tenant → pos_<slug>
     * - un-migrated or unknown → legacyDb (pos_db) as fallback
     */
    public String resolveCatalog(String tenantId) {
        if (tenantId == null || "MASTER".equalsIgnoreCase(tenantId)) {
            return masterDb;
        }
        if ("LEGACY".equalsIgnoreCase(tenantId)) {
            return legacyDb;
        }

        String cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }

        try {
            String dbName = jdbcTemplate.queryForObject(
                    "SELECT db_name FROM `" + sqlIdentifier(masterDb) + "`.tenant_databases WHERE tenant_id = ? AND status = 'MIGRATED'",
                    String.class,
                    tenantId
            );
            if (dbName != null && !dbName.isBlank()) {
                cache.put(tenantId, dbName);
                return dbName;
            }
        } catch (DataAccessException ex) {
            log.debug("Tenant database registry lookup fell back to legacy DB for tenant {}", tenantId, ex);
        }
        if (allowLegacyFallback) {
            return legacyDb;
        }
        throw new IllegalStateException("Tenant is not migrated or not registered: " + tenantId);
    }

    public TenantDatabase register(String tenantId, String dbName, MigrationStatus status) {
        LocalDateTime migratedAt = status == MigrationStatus.MIGRATED ? LocalDateTime.now() : null;
        jdbcTemplate.update("""
                INSERT INTO %s.tenant_databases (tenant_id, db_name, status, created_at, migrated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), ?)
                ON DUPLICATE KEY UPDATE
                    db_name = VALUES(db_name),
                    status = VALUES(status),
                    migrated_at = VALUES(migrated_at)
                """.formatted(qualifiedMasterSchema()), tenantId, dbName, status.name(), migratedAt);
        if (status == MigrationStatus.MIGRATED) {
            cache.put(tenantId, dbName);
        } else {
            cache.remove(tenantId);
        }
        return TenantDatabase.builder()
                .tenantId(tenantId)
                .dbName(dbName)
                .status(status)
                .migratedAt(migratedAt)
                .build();
    }

    public void evict(String tenantId) {
        cache.remove(tenantId);
    }

    /**
     * MISS-05: Return the list of all tenant IDs that have been fully migrated.
     * Used by the nightly warm-up scheduler to iterate every tenant.
     */
    public java.util.List<String> getActiveTenantIds() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT tenant_id FROM `" + sqlIdentifier(masterDb) + "`.tenant_databases WHERE status = 'MIGRATED'",
                    String.class
            );
        } catch (DataAccessException ex) {
            log.warn("Could not list active tenants for warm-up: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    /** Derive the DB name from a tenant slug: hyphens → underscores, prefix pos_ */
    public static String dbNameForTenant(String tenantId) {
        return "pos_" + tenantId.replace('-', '_');
    }

    private String sqlIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Unsafe database identifier: " + value);
        }
        return value;
    }

    private String qualifiedMasterSchema() {
        return "`" + sqlIdentifier(masterDb) + "`";
    }
}
