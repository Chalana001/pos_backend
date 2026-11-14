package com.chala.posapp.rbac;

import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacMigrationService;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRbacJdbcService {
    private final DataSource dataSource;
    private final TenantDatabaseRegistry registry;
    private final TenantRbacMigrationService migrationService;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    public String catalogForCurrentTenant() {
        return registry.resolveCatalog(TenantContext.getTenant());
    }

    public boolean readyForCurrentTenant() {
        String catalog = catalogForCurrentTenant();
        return catalog != null && !catalog.equalsIgnoreCase(legacyDb) && !catalog.equalsIgnoreCase(masterDb)
                && migrationService.isReady(catalog);
    }

    public TenantUserCreateResult createReadyTenantUser(String username, String passwordHash, String legacyRole,
                                                        boolean enabled, Long branchId) {
        return withTenantJdbc(jdbc -> {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO users (username, password_hash, role, enabled, branch_id, created_at, rbac_version)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, username);
                statement.setString(2, passwordHash);
                statement.setString(3, legacyRole);
                statement.setBoolean(4, enabled);
                if (branchId == null) statement.setObject(5, null); else statement.setLong(5, branchId);
                return statement;
            }, keyHolder);
            Number generatedId = keyHolder.getKey();
            if (generatedId == null) throw new TenantOwnershipException("User ID was not generated");
            Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE legacy_compatibility = ? AND is_system = TRUE AND is_active = TRUE ORDER BY id LIMIT 1", Long.class, legacyRole);
            if (roleId == null) throw new TenantOwnershipException("System RBAC role is missing for " + legacyRole);
            int updated = jdbc.update("UPDATE users SET role_id = ?, rbac_version = 1 WHERE id = ?", roleId, generatedId.longValue());
            if (updated != 1) throw new TenantOwnershipException("RBAC role initialization failed");
            return new TenantUserCreateResult(generatedId.longValue(), roleId, 1L);
        });
    }

    public record TenantUserCreateResult(Long userId, Long roleId, Long rbacVersion) {}

    public void updateLegacyUserStatus(Long userId, boolean enabled) {
        withLegacyJdbc(jdbc -> {
            Integer lock = jdbc.queryForObject("SELECT id FROM admin_floor_lock WHERE id = 1 FOR UPDATE", Integer.class);
            if (lock == null) throw new TenantOwnershipException("Legacy ADMIN-floor lock is unavailable");
            Map<String, Object> target;
            try {
                target = jdbc.queryForMap("SELECT role, enabled FROM users WHERE id = ?", userId);
            } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
                throw new TenantOwnershipException("User not found");
            }
            if (!enabled && "ADMIN".equals(target.get("role")) && Boolean.TRUE.equals(target.get("enabled"))) {
                Integer remaining = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND enabled = TRUE AND id <> ?",
                        Integer.class, userId);
                if (remaining == null || remaining < 1) {
                    throw new TenantOwnershipException("The last enabled operational ADMIN cannot be disabled");
                }
            }
            jdbc.update("UPDATE users SET enabled = ? WHERE id = ?", enabled, userId);
            return null;
        });
    }

    public TenantOwnershipState ownership() {
        return withTenantJdbc(jdbc -> jdbc.query(
                "SELECT id, owner_user_id, status, version FROM tenant_ownership WHERE singleton_key = 'TENANT'",
                rs -> rs.next()
                        ? new TenantOwnershipState(
                        rs.getLong("id"),
                        (Long) rs.getObject("owner_user_id"),
                        TenantOwnershipStatus.valueOf(rs.getString("status")),
                        rs.getLong("version"))
                        : new TenantOwnershipState(null, null, TenantOwnershipStatus.UNRESOLVED, 0)));
    }

    public boolean isOwner(Long userId) {
        if (userId == null) return false;
        TenantOwnershipState state = ownership();
        return state.resolved() && userId.equals(state.ownerUserId());
    }

    public TenantOwnershipState resolveFromVerifiedOnboarding(String username, Long actorUserId) {
        if (username == null || username.isBlank()) {
            throw new TenantOwnershipException("Verified onboarding username is required");
        }
        return withTenantJdbc(jdbc -> {
            TenantOwnershipState current = ownership(jdbc);
            if (current.resolved()) return current;

            List<Map<String, Object>> matches = jdbc.queryForList(
                    "SELECT id, enabled FROM users WHERE username = ?",
                    username.trim());
            if (matches.size() != 1) {
                throw new TenantOwnershipException("Tenant ownership is unresolved: onboarding user is not uniquely identified");
            }
            Map<String, Object> candidate = matches.getFirst();
            Number candidateId = (Number) candidate.get("id");
            Boolean enabled = ((Boolean) candidate.get("enabled"));
            if (!Boolean.TRUE.equals(enabled)) {
                throw new TenantOwnershipException("Tenant ownership is unresolved: onboarding user is disabled");
            }

            int updated = jdbc.update("""
                    UPDATE tenant_ownership
                    SET owner_user_id = ?, status = 'RESOLVED', version = version + 1,
                        updated_at = CURRENT_TIMESTAMP(6), updated_by_user_id = ?
                    WHERE singleton_key = 'TENANT' AND status = 'UNRESOLVED' AND version = ?
                    """, candidateId.longValue(), actorUserId, current.version());
            if (updated != 1) {
                throw new TenantOwnershipException("Tenant ownership changed concurrently; retry reconciliation");
            }
            return ownership(jdbc);
        });
    }

    public TenantOwnershipState transfer(Long actorUserId, Long targetUserId, long expectedVersion) {
        return withTenantJdbc(jdbc -> {
            TenantOwnershipState current = ownership(jdbc);
            if (!current.resolved()) {
                throw new TenantOwnershipException("Tenant ownership is unresolved");
            }
            if (!actorUserId.equals(current.ownerUserId())) {
                throw new TenantOwnershipException("Only the current tenant owner can transfer ownership");
            }
            if (current.version() != expectedVersion) {
                throw new TenantOwnershipException("Tenant ownership changed; refresh and retry");
            }
            Map<String, Object> target = jdbc.queryForMap("SELECT id, enabled FROM users WHERE id = ?", targetUserId);
            if (!Boolean.TRUE.equals(target.get("enabled"))) {
                throw new TenantOwnershipException("Disabled users cannot become tenant owners");
            }
            int updated = jdbc.update("""
                    UPDATE tenant_ownership
                    SET owner_user_id = ?, status = 'RESOLVED', version = version + 1,
                        updated_at = CURRENT_TIMESTAMP(6), updated_by_user_id = ?
                    WHERE singleton_key = 'TENANT' AND owner_user_id = ? AND version = ?
                    """, targetUserId, actorUserId, actorUserId, expectedVersion);
            if (updated != 1) {
                throw new TenantOwnershipException("Tenant ownership changed concurrently; retry transfer");
            }
            TenantOwnershipState after = ownership(jdbc);
            writeRoleAudit(jdbc, actorUserId, targetUserId, null, "OWNER_TRANSFERRED",
                    jsonOwner(current.ownerUserId(), "TRANSFERRED"), jsonOwner(targetUserId, "TRANSFERRED"),
                    "ownership transfer");
            return after;
        });
    }

    public void writeAuditOutsideTransaction(Long actorUserId, String action, Long previousOwner,
                                             Long attemptedTargetUserId, String result, String reason) {
        String tenantId = TenantContext.getTenant();
        String catalog = registry.resolveCatalog(tenantId);
        if (catalog == null || catalog.equalsIgnoreCase(legacyDb) || catalog.equalsIgnoreCase(masterDb)
                || !migrationService.isReady(catalog)) {
            throw new TenantOwnershipException("Cannot durably audit ownership outside a ready tenant catalog");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setCatalog(catalog);
            connection.setAutoCommit(true);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.update("""
                    INSERT INTO rbac_change_audit
                        (actor_user_id, target_user_id, action, before_state, after_state, reason, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, actorUserId, null, action,
                    jsonOwner(previousOwner, result), jsonOwner(attemptedTargetUserId, result), reason, LocalDateTime.now());
        } catch (SQLException ex) {
            throw new TenantOwnershipException("Ownership audit could not be durably written");
        }
    }

    public List<Map<String, Object>> audit(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return withTenantJdbc(jdbc -> jdbc.queryForList("""
                SELECT id, actor_user_id, target_user_id, role_id, action, before_state, after_state,
                       reason, created_at
                FROM rbac_change_audit ORDER BY created_at DESC, id DESC LIMIT ?
                """, bounded));
    }

    public void writeRoleAudit(JdbcTemplate jdbc, Long actorUserId, Long targetUserId, Long roleId,
                               String action, String beforeJson, String afterJson, String reason) {
        jdbc.update("""
                INSERT INTO rbac_change_audit
                    (actor_user_id, target_user_id, role_id, action, before_state, after_state, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, actorUserId, targetUserId, roleId, action, beforeJson, afterJson, reason, LocalDateTime.now());
    }

    public <T> T withLegacyJdbc(JdbcWork<T> work) {
        String catalog = catalogForCurrentTenant();
        if (catalog == null || !catalog.equalsIgnoreCase(legacyDb)) {
            throw new TenantOwnershipException("Legacy compatibility database is not active");
        }
        return withCatalogJdbc(catalog, work);
    }

    public <T> T withTenantJdbc(JdbcWork<T> work) {
        String tenantId = TenantContext.getTenant();
        String catalog = registry.resolveCatalog(tenantId);
        if (catalog == null || catalog.equalsIgnoreCase(legacyDb) || catalog.equalsIgnoreCase(masterDb)
                || !migrationService.isReady(catalog)) {
            throw new TenantOwnershipException("Tenant RBAC is not ready for this tenant");
        }
        return withCatalogJdbc(catalog, work);
    }

    private <T> T withCatalogJdbc(String catalog, JdbcWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setCatalog(catalog);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                connection.commit();
                return result;
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw new TenantOwnershipException("Tenant database access failed");
        }
    }

    private TenantOwnershipState ownership(JdbcTemplate jdbc) {
        return jdbc.query(
                "SELECT id, owner_user_id, status, version FROM tenant_ownership WHERE singleton_key = 'TENANT' FOR UPDATE",
                rs -> rs.next()
                        ? new TenantOwnershipState(rs.getLong("id"), (Long) rs.getObject("owner_user_id"),
                        TenantOwnershipStatus.valueOf(rs.getString("status")), rs.getLong("version"))
                        : new TenantOwnershipState(null, null, TenantOwnershipStatus.UNRESOLVED, 0));
    }

    private String jsonOwner(Long owner, String result) {
        return "{\"ownerUserId\":" + (owner == null ? "null" : owner) + ",\"result\":\""
                + result.replace("\"", "'") + "\"}";
    }

    @FunctionalInterface
    public interface JdbcWork<T> {
        T apply(JdbcTemplate jdbc);
    }
}
