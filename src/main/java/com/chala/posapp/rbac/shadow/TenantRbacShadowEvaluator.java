package com.chala.posapp.rbac.shadow;

import com.chala.posapp.entity.Role;
import com.chala.posapp.rbac.PermissionCatalog;
import com.chala.posapp.rbac.PermissionDefinition;
import com.chala.posapp.service.TenantDatabaseRegistry;
import com.chala.posapp.service.TenantRbacMigrationService;
import com.chala.posapp.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only, fail-soft evaluator used only for Phase 4 shadow observations. */
@Slf4j
@Service
public class TenantRbacShadowEvaluator {
    private final DataSource dataSource;
    private final TenantDatabaseRegistry registry;
    private final TenantRbacMigrationService migrationService;
    private final String legacyDb;
    private final String masterDb;
    private final boolean enabled;

    public TenantRbacShadowEvaluator(
            DataSource dataSource,
            TenantDatabaseRegistry registry,
            TenantRbacMigrationService migrationService,
            @Value("${app.datasource.legacy-db:pos_db}") String legacyDb,
            @Value("${app.datasource.master-db:pos_master}") String masterDb,
            @Value("${app.rbac.shadow-enabled:false}") boolean enabled) {
        this.dataSource = dataSource;
        this.registry = registry;
        this.migrationService = migrationService;
        this.legacyDb = legacyDb;
        this.masterDb = masterDb;
        this.enabled = enabled;
    }

    public ShadowEvaluationResult evaluate(String permissionKey) {
        return evaluateInternal(permissionKey, enabled);
    }

    /** Enforcement path is independently gated by its own feature flag. */
    public ShadowEvaluationResult evaluateForEnforcement(String permissionKey) {
        return evaluateInternal(permissionKey, true);
    }

    private ShadowEvaluationResult evaluateInternal(String permissionKey, boolean evaluationEnabled) {
        Instant started = Instant.now();
        if (!evaluationEnabled) {
            return result(permissionKey, ShadowDecision.NOT_APPLICABLE, ShadowEvaluationStatus.NOT_APPLICABLE,
                    ShadowClassification.LEGACY_ONLY, ShadowReason.TENANT_RBAC_NOT_READY, null, null, null, null,
                    Duration.between(started, Instant.now()));
        }

        PermissionDefinition definition;
        try {
            definition = PermissionCatalog.get(permissionKey);
        } catch (RuntimeException ex) {
            return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.UNMAPPED,
                    ShadowClassification.UNMAPPED, ShadowReason.UNMAPPED_ENDPOINT, null, null, null, null,
                    Duration.between(started, Instant.now()));
        }

        String tenantId = TenantContext.getTenant();
        if (tenantId == null || tenantId.isBlank() || "MASTER".equalsIgnoreCase(tenantId)) {
            return result(permissionKey, ShadowDecision.NOT_APPLICABLE, ShadowEvaluationStatus.NOT_APPLICABLE,
                    ShadowClassification.PLATFORM_ONLY, ShadowReason.PLATFORM_ONLY, null, null, null, null,
                    Duration.between(started, Instant.now()));
        }

        String catalog;
        try {
            catalog = registry.resolveCatalog(tenantId);
            if (isReservedOrLegacy(catalog)) {
                return result(permissionKey, ShadowDecision.NOT_APPLICABLE, ShadowEvaluationStatus.NOT_READY,
                        ShadowClassification.LEGACY_ONLY, ShadowReason.TENANT_RBAC_NOT_READY, null, null, null, null,
                        Duration.between(started, Instant.now()));
            }
            if (!migrationService.isReady(catalog)) {
                return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.NOT_READY,
                        ShadowClassification.MAPPED_CAPABILITY, ShadowReason.TENANT_RBAC_NOT_READY, null, null, null, null,
                        Duration.between(started, Instant.now()));
            }
        } catch (RuntimeException ex) {
            return result(permissionKey, ShadowDecision.NOT_APPLICABLE, ShadowEvaluationStatus.NOT_READY,
                    ShadowClassification.TENANT_BOUNDARY, ShadowReason.TENANT_RBAC_NOT_READY, null, null, null, null,
                    Duration.between(started, Instant.now()));
        }

        try (Connection connection = dataSource.getConnection()) {
            String originalCatalog = connection.getCatalog();
            try {
                connection.setCatalog(catalog);
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UserRow user = resolveUser(jdbc);
                if (user == null) {
                    return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.UNOBSERVED,
                            ShadowClassification.AUTHENTICATED_ONLY, ShadowReason.AMBIGUOUS_USER_IDENTITY,
                            null, null, null, null, Duration.between(started, Instant.now()));
                }
                if (user.role() == Role.SUPER_ADMIN) {
                    return result(permissionKey, ShadowDecision.NOT_APPLICABLE, ShadowEvaluationStatus.NOT_APPLICABLE,
                            ShadowClassification.PLATFORM_ONLY, ShadowReason.PLATFORM_ONLY,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }
                if (user.roleId() == null) {
                    return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.NOT_READY,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.NO_ROLE_ASSIGNMENT,
                            user.id(), null, user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }

                RoleRow role = jdbc.query(
                        "SELECT id, is_active FROM roles WHERE id = ?",
                        rs -> rs.next() ? new RoleRow(rs.getLong("id"), rs.getBoolean("is_active")) : null,
                        user.roleId());
                if (role == null || !role.active()) {
                    return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.NOT_READY,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.NO_ROLE_ASSIGNMENT,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }

                if ("users.admin.floor".equals(permissionKey) && user.role() == Role.ADMIN) {
                    return result(permissionKey, ShadowDecision.ALLOW, ShadowEvaluationStatus.EVALUATED,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.PROTECTED_PERMISSION,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }

                if (definition.protectedPermission()) {
                    return result(permissionKey, ShadowDecision.DENY, ShadowEvaluationStatus.EVALUATED,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.PROTECTED_PERMISSION,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }

                String override = jdbc.query(
                        "SELECT override_state FROM user_permission_overrides WHERE user_id = ? AND permission_key = ?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        user.id(), permissionKey);
                if ("DENY".equals(override)) {
                    return result(permissionKey, ShadowDecision.DENY, ShadowEvaluationStatus.EVALUATED,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.USER_DENY_OVERRIDE,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }
                if ("ALLOW".equals(override) && definition.individualAllowOverride()) {
                    return result(permissionKey, ShadowDecision.ALLOW, ShadowEvaluationStatus.EVALUATED,
                            ShadowClassification.MAPPED_CAPABILITY, ShadowReason.USER_ALLOW_OVERRIDE,
                            user.id(), user.roleId(), user.rbacVersion(), user.role(),
                            Duration.between(started, Instant.now()));
                }

                boolean granted = Boolean.TRUE.equals(jdbc.query(
                        "SELECT EXISTS (SELECT 1 FROM role_permissions WHERE role_id = ? AND permission_key = ?)",
                        rs -> rs.next() && rs.getBoolean(1), user.roleId(), permissionKey));
                return result(permissionKey, granted ? ShadowDecision.ALLOW : ShadowDecision.DENY,
                        ShadowEvaluationStatus.EVALUATED, ShadowClassification.MAPPED_CAPABILITY,
                        granted ? ShadowReason.ROLE_GRANT : ShadowReason.NO_GRANT,
                        user.id(), user.roleId(), user.rbacVersion(), user.role(),
                        Duration.between(started, Instant.now()));
            } finally {
                restore(connection, originalCatalog);
            }
        } catch (Exception ex) {
            log.debug("Shadow RBAC evaluation failed softly. permissionKey={}, reason={}", permissionKey, ex.getMessage());
            return result(permissionKey, ShadowDecision.UNKNOWN, ShadowEvaluationStatus.ERROR,
                    ShadowClassification.MAPPED_CAPABILITY, ShadowReason.EVALUATION_ERROR, null, null, null, null,
                    Duration.between(started, Instant.now()));
        }
    }

    private UserRow resolveUser(JdbcTemplate jdbc) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        Object trustedId = attributes == null ? null
                : attributes.getRequest().getAttribute(ShadowRequestAttributes.AUTHENTICATED_USER_ID);
        if (trustedId instanceof Number number) {
            UserRow user = jdbc.query(
                    "SELECT id, role, role_id, rbac_version FROM users WHERE id = ?",
                    rs -> rs.next() ? new UserRow(rs.getLong("id"), parseRole(rs.getString("role")),
                            (Long) rs.getObject("role_id"), rs.getLong("rbac_version")) : null,
                    number.longValue());
            return user;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        List<UserRow> users = jdbc.query(
                "SELECT id, role, role_id, rbac_version FROM users WHERE username = ?",
                (rs, rowNum) -> new UserRow(rs.getLong("id"), parseRole(rs.getString("role")),
                        (Long) rs.getObject("role_id"), rs.getLong("rbac_version")),
                authentication.getName());
        return users.size() == 1 ? users.getFirst() : null;
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isReservedOrLegacy(String catalog) {
        return catalog == null || catalog.equalsIgnoreCase(legacyDb) || catalog.equalsIgnoreCase(masterDb)
                || catalog.equalsIgnoreCase("pos_db") || catalog.equalsIgnoreCase("pos_master");
    }

    private void restore(Connection connection, String originalCatalog) {
        try {
            connection.setCatalog(originalCatalog == null || originalCatalog.isBlank() ? legacyDb : originalCatalog);
        } catch (SQLException ex) {
            log.debug("Could not restore catalog after shadow evaluation: {}", ex.getMessage());
        }
    }

    private ShadowEvaluationResult result(String key, ShadowDecision decision, ShadowEvaluationStatus status,
                                          ShadowClassification classification, ShadowReason reason,
                                          Long userId, Long roleId, Long version, Role role, Duration duration) {
        return new ShadowEvaluationResult(key, decision, status, classification, reason,
                userId, roleId, version, role, "tenant-username-unique", duration);
    }

    private record UserRow(Long id, Role role, Long roleId, Long rbacVersion) {
    }

    private record RoleRow(Long id, boolean active) {
    }
}
