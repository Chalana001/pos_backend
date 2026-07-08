package com.chala.posapp.util;

import com.chala.posapp.tenant.TenantContext;

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

    private static String tenant() {
        String t = TenantContext.getTenant();
        return t != null ? t : "_global_";
    }
}
