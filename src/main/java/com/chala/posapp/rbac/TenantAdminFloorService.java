package com.chala.posapp.rbac;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TenantAdminFloorService {
    public static final Set<String> MANDATORY_ADMIN_PERMISSIONS = Set.of(
            "users.view", "users.manage", "staff.view", "staff.manage");

    private final TenantRbacJdbcService jdbcService;

    public <T> T withAdminFloorLock(AdminFloorWork<T> work) {
        return jdbcService.withTenantJdbc(jdbc -> {
            Integer lockId = jdbc.query("SELECT id FROM tenant_ownership WHERE singleton_key = 'TENANT' FOR UPDATE",
                    rs -> rs.next() ? rs.getInt(1) : null);
            if (lockId == null) throw new TenantOwnershipException("Tenant ADMIN-floor lock is unavailable");
            return work.apply(jdbc);
        });
    }

    public void requireMandatoryAdminCapabilities(Set<String> proposedPermissions) {
        Set<String> missing = new LinkedHashSet<>(MANDATORY_ADMIN_PERMISSIONS);
        missing.removeAll(proposedPermissions == null ? Set.of() : proposedPermissions);
        if (!missing.isEmpty()) {
            throw new TenantOwnershipException("Role does not preserve mandatory ADMIN capabilities: " + missing);
        }
    }

    public void requireMandatoryAdminCapabilities(JdbcTemplate jdbc, Long roleId) {
        Set<String> actual = Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId));
        requireMandatoryAdminCapabilities(actual);
    }

    public boolean hasEnabledAdminAssignment(JdbcTemplate jdbc, Long roleId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users
                WHERE role_id = ? AND role = 'ADMIN' AND enabled = TRUE
                """, Integer.class, roleId);
        return count != null && count > 0;
    }

    public void requireRoleChangePreservesAdminCapabilities(JdbcTemplate jdbc, Long targetUserId, Long nextRoleId) {
        MapRow target = jdbc.query("SELECT role, enabled FROM users WHERE id = ?",
                rs -> rs.next() ? new MapRow(rs.getString("role"), rs.getBoolean("enabled")) : null, targetUserId);
        if (target != null && target.enabled() && "ADMIN".equals(target.role())) {
            requireMandatoryAdminCapabilities(jdbc, nextRoleId);
        }
    }

    public void requireAdminFloorAfterStatusChange(JdbcTemplate jdbc, Long targetUserId, boolean enabled) {
        if (enabled) return;
        String role = jdbc.query("SELECT role FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, targetUserId);
        if (!"ADMIN".equals(role)) return;
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND enabled = TRUE AND id <> ?",
                Integer.class, targetUserId);
        if (remaining == null || remaining < 1) {
            throw new TenantOwnershipException("The last enabled operational ADMIN cannot be disabled");
        }
    }

    public void requireAdminFloorAfterRoleChange(JdbcTemplate jdbc, Long targetUserId, String nextLegacyRole) {
        if ("ADMIN".equals(nextLegacyRole)) return;
        String currentRole = jdbc.query("SELECT role FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, targetUserId);
        if (!"ADMIN".equals(currentRole)) return;
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND enabled = TRUE AND id <> ?",
                Integer.class, targetUserId);
        if (remaining == null || remaining < 1) {
            throw new TenantOwnershipException("The last enabled operational ADMIN cannot be demoted");
        }
    }

    private record MapRow(String role, boolean enabled) {}

    @FunctionalInterface
    public interface AdminFloorWork<T> {
        T apply(JdbcTemplate jdbc);
    }
}
