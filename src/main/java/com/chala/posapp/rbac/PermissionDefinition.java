package com.chala.posapp.rbac;

import java.util.Objects;

/**
 * Immutable metadata for one capability permission.
 *
 * <p>This describes capability only. It does not grant access around subscription/module,
 * tenant, branch, resource ownership, workflow, support, or platform boundaries.</p>
 */
public record PermissionDefinition(
        String key,
        String label,
        String description,
        PermissionCategory category,
        RiskClass risk,
        boolean tenantGrantable,
        boolean individualAllowOverride,
        boolean protectedPermission
) {
    public PermissionDefinition {
        key = requireText(key, "key");
        label = requireText(label, "label");
        description = requireText(description, "description");
        category = Objects.requireNonNull(category, "category");
        risk = Objects.requireNonNull(risk, "risk");
        if (!key.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")) {
            throw new IllegalArgumentException("Permission key must use stable dot notation: " + key);
        }
        if (protectedPermission && (tenantGrantable || individualAllowOverride)) {
            throw new IllegalArgumentException("Protected permission cannot be tenant-grantable or individually allowed: " + key);
        }
    }

    private static String requireText(String value, String field) {
        String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
