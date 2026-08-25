package com.chala.posapp.util;

import com.chala.posapp.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * MISS-01: Tenant-scoped cache key helpers.
 *
 * Every cache key MUST include the tenant ID so tenants never see each other's
 * cached data.  Use {@code key()} for single-arg keys and {@code key(args...)}
 * for composite ones.
 *
 * Usage inside @Cacheable:
 *   @Cacheable(value = CacheConfig.CACHE_DASHBOARD_KPIS,
 *              key    = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)")
 */
public final class CacheKeyUtils {

    private CacheKeyUtils() {}

    /** e.g. "tenant1:42" */
    public static String key(Object arg) {
        return tenant() + ":" + arg;
    }

    /** e.g. "tenant1:42:2026-01-01:2026-01-31" */
    public static String key(Object... args) {
        StringBuilder sb = new StringBuilder(tenant());
        for (Object a : args) {
            sb.append(':').append(a);
        }
        return sb.toString();
    }

    /**
     * Like {@link #key(Object...)} but also namespaced by the caller's branch scope.
     *
     * <p>Report services take the branch the caller <em>asked for</em> and then clamp it:
     * an admin keeps it, anyone else is forced onto their own assigned branch. Keying on
     * the raw request parameter therefore stored a clamped payload under the branch that
     * was requested — a branch-1 manager asking for branch 3 wrote branch 1's figures to
     * the branch 3 key, and the next admin to open branch 3 was served them.
     *
     * <p>Adding the scope makes that impossible. Admins share one namespace because they
     * are never clamped, so for them requested and resolved are the same value. Everyone
     * else gets their own namespace, keyed on the principal name, so a clamped result can
     * only ever be read back by the user it was clamped for.
     *
     * <p>Read from the {@code Authentication} rather than the database — this runs on
     * every cached call, and the authorities are already on the token.
     */
    public static String scopedKey(Object... args) {
        StringBuilder sb = new StringBuilder(tenant()).append(':').append(scope());
        for (Object a : args) {
            sb.append(':').append(a);
        }
        return sb.toString();
    }

    private static String scope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "_system_";
        }
        boolean adminLike = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_SUPER_ADMIN".equals(role));
        return adminLike ? "_admin_" : auth.getName();
    }

    private static String tenant() {
        String t = TenantContext.getTenant();
        return t != null ? t : "_global_";
    }
}
