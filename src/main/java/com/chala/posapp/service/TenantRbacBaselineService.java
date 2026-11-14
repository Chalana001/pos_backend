package com.chala.posapp.service;

import com.chala.posapp.entity.Role;
import com.chala.posapp.rbac.DefaultRoleGrants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the non-enforcing RBAC compatibility baseline inside one isolated tenant catalog.
 *
 * <p>This service deliberately uses JDBC rather than a second tenant routing abstraction. The
 * caller owns catalog selection and must pass an isolated catalog; legacy users.role remains the
 * only authorization source until a later phase enables dynamic evaluation.</p>
 */
@Slf4j
@Service
public class TenantRbacBaselineService {
    private static final Map<Role, String> ROLE_KEYS = Map.of(
            Role.ADMIN, "DEFAULT_ADMIN",
            Role.MANAGER, "DEFAULT_MANAGER",
            Role.CASHIER, "DEFAULT_CASHIER");

    private final DataSource dataSource;
    private final String legacyDb;
    private final String masterDb;

    public TenantRbacBaselineService(
            DataSource dataSource,
            @Value("${app.datasource.legacy-db:pos_db}") String legacyDb,
            @Value("${app.datasource.master-db:pos_master}") String masterDb) {
        this.dataSource = dataSource;
        this.legacyDb = legacyDb;
        this.masterDb = masterDb;
    }

    public BaselineResult seedAndBackfill(String catalog) {
        validateCatalog(catalog);
        try (Connection connection = dataSource.getConnection()) {
            String originalCatalog = connection.getCatalog();
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setCatalog(catalog);
                connection.setAutoCommit(false);
                JdbcTemplate tenantJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                BaselineResult result = reconcileSelectedCatalog(catalog, tenantJdbc);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                if (ex instanceof BaselineException baselineException) {
                    throw baselineException;
                }
                throw new BaselineException("RBAC baseline failed for catalog '" + catalog + "'", ex);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                    connection.setCatalog(originalCatalog == null || originalCatalog.isBlank() ? legacyDb : originalCatalog);
                } catch (SQLException restoreError) {
                    log.warn("Could not restore datasource catalog after RBAC baseline: {}", restoreError.getMessage());
                }
            }
        } catch (SQLException ex) {
            throw new BaselineException("RBAC baseline failed for catalog '" + catalog + "'", ex);
        }
    }

    /** Reconciles an already-selected catalog using a connection-bound template. */
    public BaselineResult reconcileSelectedCatalog(String catalog, JdbcTemplate tenantJdbc) {
        validateCatalog(catalog);
        Map<Role, Long> roleIds = new EnumMap<>(Role.class);
        Map<Role, Integer> insertedGrants = new EnumMap<>(Role.class);
        int createdRoles = 0;
        int preservedRoles = 0;

        for (Map.Entry<Role, String> entry : ROLE_KEYS.entrySet()) {
            Role role = entry.getKey();
            String roleKey = entry.getValue();
            Long roleId = findRoleId(tenantJdbc, roleKey);
            if (roleId == null) {
                roleId = insertRole(tenantJdbc, roleKey, role);
                createdRoles++;
            } else {
                preservedRoles++;
                tenantJdbc.update("""
                        UPDATE roles SET role_type = 'SYSTEM', legacy_compatibility = ?, is_system = TRUE,
                               is_protected = ?, is_active = TRUE
                        WHERE id = ?
                        """, role.name(), role == Role.ADMIN, roleId);
            }
            roleIds.put(role, roleId);
            insertedGrants.put(role, insertMissingGrants(tenantJdbc, roleId, DefaultRoleGrants.forRole(role)));
        }

        List<UnmappedUser> unmapped = new ArrayList<>();
        int backfilled = 0;
        int preservedAssociations = 0;
        List<UserRow> users = tenantJdbc.query(
                "SELECT id, username, role, role_id FROM users",
                (rs, rowNum) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                        rs.getString("role"), (Long) rs.getObject("role_id")));
        for (UserRow user : users) {
            if (user.roleId() != null) {
                preservedAssociations++;
                continue;
            }
            Role role = parseTenantRole(user.role());
            if (role == null) {
                unmapped.add(new UnmappedUser(user.id(), user.username(), user.role()));
                continue;
            }
            int updated = tenantJdbc.update("UPDATE users SET role_id = ?, rbac_version = COALESCE(rbac_version, 0) + 1 WHERE id = ? AND role_id IS NULL",
                    roleIds.get(role), user.id());
            backfilled += updated;
        }

        BaselineResult result = new BaselineResult(catalog, createdRoles, preservedRoles, roleIds,
                insertedGrants, backfilled, preservedAssociations, List.copyOf(unmapped));
        log.info("Tenant RBAC baseline reconciled. catalog={}, createdRoles={}, preservedRoles={}, "
                        + "backfilledUsers={}, preservedAssociations={}, unmappedUsers={}",
                catalog, createdRoles, preservedRoles, backfilled, preservedAssociations, unmapped.size());
        return result;
    }

    public void validateCatalog(String catalog) {
        if (catalog == null || !catalog.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid isolated tenant catalog: " + catalog);
        }
        if (catalog.equalsIgnoreCase(legacyDb) || catalog.equalsIgnoreCase(masterDb)
                || catalog.equalsIgnoreCase("pos_db") || catalog.equalsIgnoreCase("pos_master")) {
            throw new IllegalArgumentException("Reserved catalog cannot receive tenant RBAC baseline: " + catalog);
        }
    }

    private Long findRoleId(JdbcTemplate jdbc, String roleKey) {
        List<Long> ids = jdbc.query("SELECT id FROM roles WHERE role_key = ?", (rs, rowNum) -> rs.getLong(1), roleKey);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long insertRole(JdbcTemplate jdbc, String roleKey, Role role) {
        jdbc.update("""
                INSERT INTO roles (role_key, display_name, is_system, role_type, is_protected, is_active, legacy_compatibility)
                VALUES (?, ?, TRUE, 'SYSTEM', ?, TRUE, ?)
                """, roleKey, role.name(), role == Role.ADMIN, role.name());
        return findRoleId(jdbc, roleKey);
    }

    private int insertMissingGrants(JdbcTemplate jdbc, Long roleId, Iterable<String> permissions) {
        int inserted = 0;
        for (String permission : permissions) {
            inserted += jdbc.update("""
                    INSERT INTO role_permissions (role_id, permission_key)
                    SELECT ?, ? FROM DUAL
                    WHERE NOT EXISTS (
                        SELECT 1 FROM role_permissions WHERE role_id = ? AND permission_key = ?
                    )
                    """, roleId, permission, roleId, permission);
        }
        return inserted;
    }

    private Role parseTenantRole(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Role role = Role.valueOf(value.trim().toUpperCase());
            return role == Role.SUPER_ADMIN ? null : role;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record BaselineResult(
            String catalog,
            int createdRoles,
            int preservedRoles,
            Map<Role, Long> roleIds,
            Map<Role, Integer> insertedGrants,
            int backfilledUsers,
            int preservedAssociations,
            List<UnmappedUser> unmappedUsers) {
        public BaselineResult {
            roleIds = Map.copyOf(new EnumMap<>(roleIds));
            insertedGrants = Map.copyOf(new EnumMap<>(insertedGrants));
            unmappedUsers = List.copyOf(unmappedUsers);
        }
    }

    public record UnmappedUser(Long id, String username, String legacyRole) {
    }

    private record UserRow(Long id, String username, String role, Long roleId) {
    }

    public static class BaselineException extends RuntimeException {
        public BaselineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
