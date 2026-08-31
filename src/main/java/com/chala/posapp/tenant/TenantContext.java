package com.chala.posapp.tenant;

import java.util.function.Supplier;

public class TenantContext {

    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    public static void setTenant(String tenant) {
        currentTenant.set(tenant);
    }

    public static String getTenant() {
        return currentTenant.get();
    }

    public static void clear() {
        currentTenant.remove();
    }

    /** Run supplier under a specific tenant, then restore the previous context. */
    public static <T> T callWith(String tenant, Supplier<T> supplier) {
        String previous = currentTenant.get();
        currentTenant.set(tenant);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                currentTenant.remove();
            } else {
                currentTenant.set(previous);
            }
        }
    }

    /** Void variant of callWith. */
    public static void runWith(String tenant, Runnable action) {
        callWith(tenant, () -> { action.run(); return null; });
    }
}