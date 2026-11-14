package com.chala.posapp.rbac;

import com.chala.posapp.dto.rbac.CreateRoleRequest;
import com.chala.posapp.dto.rbac.RoleResponse;
import com.chala.posapp.dto.rbac.UpdateRoleRequest;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.ConflictException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RoleManagementService {
    private static final Pattern ROLE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");

    private final TenantRbacJdbcService jdbcService;
    private final RbacAuthorizationService authorization;
    private final SecurityUtils securityUtils;
    private final TenantAdminFloorService adminFloorService;

    public List<RoleResponse> list() {
        requireRoleReader(securityUtils.getCurrentUser());
        return jdbcService.withTenantJdbc(jdbc -> jdbc.query("""
                SELECT r.id, r.role_key, r.display_name, r.role_type, r.is_system, r.is_protected,
                       r.is_active, r.legacy_compatibility, r.template_role_id, r.version
                FROM roles r ORDER BY r.is_system DESC, r.display_name ASC
                """, (rs, rowNum) -> response(jdbc, rs.getLong("id"), rs.getString("role_key"),
                rs.getString("display_name"), rs.getString("role_type"), rs.getBoolean("is_system"),
                rs.getBoolean("is_protected"), rs.getBoolean("is_active"),
                rs.getString("legacy_compatibility"), (Long) rs.getObject("template_role_id"),
                rs.getLong("version"))));
    }

    public RoleResponse get(Long roleId) {
        requireRoleReader(securityUtils.getCurrentUser());
        return jdbcService.withTenantJdbc(jdbc -> jdbc.query("""
                SELECT r.id, r.role_key, r.display_name, r.role_type, r.is_system, r.is_protected,
                       r.is_active, r.legacy_compatibility, r.template_role_id, r.version
                FROM roles r WHERE r.id = ?
                """, rs -> rs.next() ? response(jdbc, rs.getLong("id"), rs.getString("role_key"),
                rs.getString("display_name"), rs.getString("role_type"), rs.getBoolean("is_system"),
                rs.getBoolean("is_protected"), rs.getBoolean("is_active"),
                rs.getString("legacy_compatibility"), (Long) rs.getObject("template_role_id"),
                rs.getLong("version")) : null, roleId));
    }

    public RoleResponse create(CreateRoleRequest request) {
        User actor = securityUtils.getCurrentUser();
        requireRoleManager(actor);
        validateKey(request.getRoleKey());
        Set<String> keys = request.getPermissionKeys() == null ? Set.of() : request.getPermissionKeys();
        authorization.validateGrantKeys(keys);
        return adminFloorService.withAdminFloorLock(jdbc -> {
            Long templateId = request.getTemplateRoleId();
            Set<String> grants = templateId == null ? keys : loadSnapshot(jdbc, templateId);
            if (templateId != null && getRoleRow(jdbc, templateId) == null) {
                throw new ResourceNotFoundException("Template role not found");
            }
            if (templateId != null && isAdminCompatibleTemplate(jdbc, templateId)) {
                adminFloorService.requireMandatoryAdminCapabilities(grants);
            }
            jdbc.update("""
                    INSERT INTO roles (role_key, display_name, is_system, is_protected, is_active,
                                       role_type, template_role_id, legacy_compatibility)
                    VALUES (?, ?, FALSE, FALSE, TRUE, 'CUSTOM', ?, NULL)
                    """, request.getRoleKey().trim(), request.getDisplayName().trim(), templateId);
            Long id = jdbc.queryForObject("SELECT id FROM roles WHERE role_key = ?", Long.class, request.getRoleKey().trim());
            for (String key : grants) {
                jdbc.update("INSERT INTO role_permissions (role_id, permission_key, changed_by_user_id) VALUES (?, ?, ?)",
                        id, key, actor.getId());
            }
            jdbcService.writeRoleAudit(jdbc, actor.getId(), null, id, "ROLE_CREATED", null,
                    "{\"permissionKeys\":" + grants.size() + "}", "custom role created");
            return getWithin(jdbc, id);
        });
    }

    public RoleResponse update(Long roleId, UpdateRoleRequest request) {
        User actor = securityUtils.getCurrentUser();
        requireRoleManager(actor);
        authorization.validateGrantKeys(request.getPermissionKeys());
        return adminFloorService.withAdminFloorLock(jdbc -> {
            RoleRow role = getRoleRow(jdbc, roleId);
            requireCustom(role);
            if (role.version() != request.getExpectedVersion()) {
                throw new ConflictException("Role changed; refresh and retry");
            }
            if (adminFloorService.hasEnabledAdminAssignment(jdbc, roleId)) {
                adminFloorService.requireMandatoryAdminCapabilities(request.getPermissionKeys());
            }
            jdbc.update("UPDATE roles SET display_name = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND version = ?",
                    request.getDisplayName().trim(), roleId, request.getExpectedVersion());
            jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
            for (String key : request.getPermissionKeys()) {
                jdbc.update("INSERT INTO role_permissions (role_id, permission_key, changed_by_user_id) VALUES (?, ?, ?)",
                        roleId, key, actor.getId());
            }
            jdbcService.writeRoleAudit(jdbc, actor.getId(), null, roleId, "ROLE_UPDATED", null,
                    "{\"permissionKeys\":" + request.getPermissionKeys().size() + "}", "custom role updated");
            return getWithin(jdbc, roleId);
        });
    }

    public RoleResponse setActive(Long roleId, boolean active, long expectedVersion) {
        User actor = securityUtils.getCurrentUser();
        requireRoleManager(actor);
        return adminFloorService.withAdminFloorLock(jdbc -> {
            RoleRow role = getRoleRow(jdbc, roleId);
            requireCustom(role);
            if (role.version() != expectedVersion) throw new ConflictException("Role changed; refresh and retry");
            if (!active && assignedCount(jdbc, roleId) > 0) {
                throw new ConflictException("Role is assigned to employees and cannot be deactivated");
            }
            jdbc.update("UPDATE roles SET is_active = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND version = ?",
                    active, roleId, expectedVersion);
            jdbcService.writeRoleAudit(jdbc, actor.getId(), null, roleId, active ? "ROLE_ACTIVATED" : "ROLE_DEACTIVATED",
                    null, "{\"active\":" + active + "}", "custom role status changed");
            return getWithin(jdbc, roleId);
        });
    }

    public Set<String> effectivePermissions(Long userId) {
        User actor = securityUtils.getCurrentUser();
        if (!actor.getId().equals(userId) && !authorization.canManageOperationalRoles(actor)) {
            throw new TenantOwnershipException("Effective permission access denied");
        }
        return jdbcService.withTenantJdbc(jdbc -> jdbc.queryForList("""
                SELECT rp.permission_key FROM users u
                JOIN role_permissions rp ON rp.role_id = u.role_id
                JOIN roles r ON r.id = u.role_id AND r.is_active = TRUE
                WHERE u.id = ?
                """, String.class, userId).stream().collect(java.util.stream.Collectors.toSet()));
    }

    public void assign(Long userId, Long roleId, long expectedUserVersion) {
        User actor = securityUtils.getCurrentUser();
        requireRoleManager(actor);
        adminFloorService.withAdminFloorLock(jdbc -> {
            RoleRow role = getRoleRow(jdbc, roleId);
            if (role == null || !role.active()) throw new ResourceNotFoundException("Active role not found");
            if ("ADMIN".equals(role.legacyCompatibility())) {
                adminFloorService.requireMandatoryAdminCapabilities(jdbc, role.id());
            }
            adminFloorService.requireRoleChangePreservesAdminCapabilities(jdbc, userId, roleId);
            Long currentVersion = jdbc.queryForObject("SELECT COALESCE(rbac_version, 0) FROM users WHERE id = ?", Long.class, userId);
            if (currentVersion == null || currentVersion != expectedUserVersion) {
                throw new ConflictException("User role changed; refresh and retry");
            }
            String legacyRole = role.legacyCompatibility() == null || role.legacyCompatibility().isBlank()
                    ? "CUSTOM" : role.legacyCompatibility();
            if ("NONE".equals(legacyRole)) legacyRole = "CUSTOM";
            if (!Set.of("CUSTOM", "CASHIER", "MANAGER", "ADMIN").contains(legacyRole)) {
                throw new TenantOwnershipException("Invalid legacy compatibility mapping");
            }
            adminFloorService.requireAdminFloorAfterRoleChange(jdbc, userId, legacyRole);
            jdbc.update("UPDATE users SET role_id = ?, role = ?, rbac_version = rbac_version + 1 WHERE id = ? AND rbac_version = ?",
                    roleId, legacyRole, userId, expectedUserVersion);
            jdbcService.writeRoleAudit(jdbc, actor.getId(), userId, roleId, "USER_ROLE_ASSIGNED", null,
                    "{\"roleId\":" + roleId + "}", "operational role assigned");
            return null;
        });
    }

    public List<java.util.Map<String, Object>> audit(int limit) {
        User actor = securityUtils.getCurrentUser();
        requireRoleReader(actor);
        return jdbcService.audit(limit);
    }

    public long rbacVersion(Long userId) {
        User actor = securityUtils.getCurrentUser();
        if (!actor.getId().equals(userId) && !authorization.canManageOperationalRoles(actor)) {
            throw new TenantOwnershipException("RBAC version access denied");
        }
        return jdbcService.withTenantJdbc(jdbc -> jdbc.queryForObject(
                "SELECT COALESCE(rbac_version, 0) FROM users WHERE id = ?", Long.class, userId));
    }

    private RoleResponse getWithin(org.springframework.jdbc.core.JdbcTemplate jdbc, Long roleId) {
        RoleRow row = getRoleRow(jdbc, roleId);
        if (row == null) throw new ResourceNotFoundException("Role not found");
        return response(jdbc, row.id(), row.roleKey(), row.displayName(), row.roleType(), row.system(), row.protectedRole(),
                row.active(), row.legacyCompatibility(), row.templateRoleId(), row.version());
    }

    private RoleResponse response(org.springframework.jdbc.core.JdbcTemplate jdbc, Long id, String key, String name,
                                  String type, boolean system, boolean protectedRole, boolean active,
                                  String compatibility, Long templateId, long version) {
        List<String> grants = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ? ORDER BY permission_key",
                String.class, id);
        return RoleResponse.builder().id(id).roleKey(key).displayName(name).roleType(type).system(system)
                .protectedRole(protectedRole).active(active).legacyCompatibility(compatibility)
                .templateRoleId(templateId).version(version).permissionKeys(grants).build();
    }

    private Set<String> loadSnapshot(org.springframework.jdbc.core.JdbcTemplate jdbc, Long roleId) {
        if (getRoleRow(jdbc, roleId) == null) throw new ResourceNotFoundException("Template role not found");
        return Set.copyOf(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId));
    }

    private RoleRow getRoleRow(org.springframework.jdbc.core.JdbcTemplate jdbc, Long id) {
        List<RoleRow> rows = jdbc.query("SELECT id, role_key, display_name, role_type, is_system, is_protected, is_active, legacy_compatibility, template_role_id, version FROM roles WHERE id = ?",
                (rs, rowNum) -> new RoleRow(rs.getLong("id"), rs.getString("role_key"), rs.getString("display_name"),
                        rs.getString("role_type"), rs.getBoolean("is_system"), rs.getBoolean("is_protected"),
                        rs.getBoolean("is_active"), rs.getString("legacy_compatibility"),
                        (Long) rs.getObject("template_role_id"), rs.getLong("version")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int assignedCount(org.springframework.jdbc.core.JdbcTemplate jdbc, Long roleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id = ?", Integer.class, roleId);
    }

    private boolean isAdminCompatibleTemplate(org.springframework.jdbc.core.JdbcTemplate jdbc, Long roleId) {
        RoleRow template = getRoleRow(jdbc, roleId);
        return template != null && "ADMIN".equalsIgnoreCase(template.legacyCompatibility());
    }

    private void requireRoleReader(User user) {
        if (!authorization.canManageOperationalRoles(user)
                && user.getRole() != com.chala.posapp.entity.Role.MANAGER) {
            throw new TenantOwnershipException("Operational role access denied");
        }
    }

    private void requireRoleManager(User user) {
        if (!authorization.canManageOperationalRoles(user)) throw new TenantOwnershipException("Operational role management denied");
    }

    private void requireCustom(RoleRow role) {
        if (role == null) throw new ResourceNotFoundException("Role not found");
        if (role.system() || role.protectedRole() || !"CUSTOM".equals(role.roleType())) {
            throw new TenantOwnershipException("System roles cannot be edited or deactivated");
        }
    }

    private void validateKey(String key) {
        if (key == null || !ROLE_KEY.matcher(key.trim()).matches() || key.startsWith("DEFAULT_")) {
            throw new TenantOwnershipException("Invalid or reserved custom role key");
        }
    }

    private record RoleRow(Long id, String roleKey, String displayName, String roleType, boolean system,
                           boolean protectedRole, boolean active, String legacyCompatibility,
                           Long templateRoleId, long version) {}
}
