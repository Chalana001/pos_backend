package com.chala.posapp.service;

import com.chala.posapp.dto.saas.SystemHealthResponse;
import com.chala.posapp.dto.saas.TenantHealthResponse;
import com.chala.posapp.entity.AppModule;
import com.chala.posapp.entity.TenantDatabase;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.module.ModuleCatalog;
import com.chala.posapp.module.ModuleRouteResolver;
import com.chala.posapp.repository.AppModuleRepository;
import com.chala.posapp.repository.TenantDatabaseRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Operational visibility over the tenant estate.
 *
 * <p>Reads every shop's catalog directly through {@link JdbcTemplate} rather than through JPA
 * and {@code TenantContext}. That is deliberate: this page exists precisely to show shops whose
 * schema is behind or whose database is missing, and a JPA read against such a catalog throws
 * before it can report anything. Here a failure becomes {@code reachable = false} plus the
 * message, which is the information the operator actually wants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminHealthService {

    /** Catalog names are written by provisioning, but never interpolate one unvalidated. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final AppModuleRepository appModuleRepository;
    private final ModuleRouteResolver routeResolver;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Transactional(readOnly = true)
    public TenantHealthResponse getTenantHealth(String tenantId) {
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + tenantId));
        TenantDatabase database = tenantDatabaseRepository.findByTenantId(tenantId).orElse(null);
        return probe(subscription, database);
    }

    @Transactional(readOnly = true)
    public List<TenantHealthResponse> getAllTenantHealth() {
        Map<String, TenantDatabase> databases = new LinkedHashMap<>();
        tenantDatabaseRepository.findAll().forEach(row -> databases.put(row.getTenantId(), row));

        return tenantSubscriptionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(subscription -> probe(subscription, databases.get(subscription.getTenantId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SystemHealthResponse getSystemHealth() {
        LocalDateTime now = LocalDateTime.now();

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (TenantDatabase database : tenantDatabaseRepository.findAll()) {
            statusCounts.merge(database.getStatus().name(), 1L, Long::sum);
        }

        List<TenantHealthResponse> health = getAllTenantHealth();
        Map<String, Long> schemaVersions = new LinkedHashMap<>();
        int unreachable = 0;
        for (TenantHealthResponse row : health) {
            if (!row.reachable()) {
                unreachable++;
                continue;
            }
            schemaVersions.merge(row.schemaVersion() == null ? "unknown" : row.schemaVersion(), 1L, Long::sum);
        }

        List<String> inactiveModules = appModuleRepository.findAll().stream()
                .filter(module -> !module.isActive())
                .map(AppModule::getModuleKey)
                .sorted()
                .toList();

        return new SystemHealthResponse(
                tenantSubscriptionRepository.count(),
                tenantSubscriptionRepository.countByIsActiveTrueAndBlockedFalseAndValidUntilAfter(now),
                tenantSubscriptionRepository.countByValidUntilBefore(now),
                tenantSubscriptionRepository.countByBlockedTrue(),
                statusCounts,
                schemaVersions,
                unreachable,
                routeResolver.unmappedPaths(),
                inactiveModules,
                ModuleCatalog.all().size(),
                masterDb);
    }

    private TenantHealthResponse probe(TenantSubscription subscription, TenantDatabase database) {
        String tenantId = subscription.getTenantId();
        String dbName = database != null ? database.getDbName() : null;
        String status = database != null ? database.getStatus().name() : "MISSING";
        LocalDateTime migratedAt = database != null ? database.getMigratedAt() : null;

        if (dbName == null || !SAFE_IDENTIFIER.matcher(dbName).matches()) {
            return unreachable(subscription, dbName, status, migratedAt,
                    dbName == null ? "No tenant database registered" : "Unsafe database name: " + dbName);
        }

        try {
            // Order by installed_rank rather than MAX(version): version is a string, and
            // casting it to a decimal to sort turns "29" into "29.00" in the output.
            String schemaVersion = scalar(dbName,
                    "SELECT version FROM `%s`.flyway_schema_history "
                            + "WHERE success = 1 AND version IS NOT NULL "
                            + "ORDER BY installed_rank DESC LIMIT 1",
                    String.class).orElse(null);

            return new TenantHealthResponse(
                    tenantId,
                    subscription.getShopName(),
                    dbName,
                    status,
                    migratedAt,
                    schemaVersion,
                    true,
                    null,
                    count(dbName, "branches"),
                    count(dbName, "users"),
                    count(dbName, "items"),
                    count(dbName, "orders"),
                    timestamp(dbName, "SELECT MAX(created_at) FROM `%s`.orders"),
                    null);
        } catch (Exception exception) {
            log.warn("Health probe failed for tenant {} ({}): {}", tenantId, dbName, exception.getMessage());
            return unreachable(subscription, dbName, status, migratedAt, exception.getMessage());
        }
    }

    private TenantHealthResponse unreachable(TenantSubscription subscription, String dbName, String status,
                                             LocalDateTime migratedAt, String error) {
        return new TenantHealthResponse(
                subscription.getTenantId(), subscription.getShopName(), dbName, status, migratedAt,
                null, false, error, 0, 0, 0, 0, null, null);
    }

    private long count(String dbName, String table) {
        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            return 0L;
        }
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + dbName + "`.`" + table + "`", Long.class);
            return value == null ? 0L : value;
        } catch (Exception exception) {
            // A table missing on an older schema is not a failure of the whole probe.
            return 0L;
        }
    }

    private <T> Optional<T> scalar(String dbName, String sqlTemplate, Class<T> type) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(String.format(sqlTemplate, dbName), type));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private LocalDateTime timestamp(String dbName, String sqlTemplate) {
        try {
            Timestamp value = jdbcTemplate.queryForObject(String.format(sqlTemplate, dbName), Timestamp.class);
            return value == null ? null : value.toLocalDateTime();
        } catch (Exception exception) {
            return null;
        }
    }

    /** Kept for callers that want an empty list rather than a throw on a bad estate. */
    public List<String> safeIdentifierIssues() {
        List<String> issues = new ArrayList<>();
        for (TenantDatabase database : tenantDatabaseRepository.findAll()) {
            if (database.getDbName() == null || !SAFE_IDENTIFIER.matcher(database.getDbName()).matches()) {
                issues.add(database.getTenantId() + " → " + database.getDbName());
            }
        }
        return issues;
    }
}
