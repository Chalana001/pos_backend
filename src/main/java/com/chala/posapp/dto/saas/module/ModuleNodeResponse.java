package com.chala.posapp.dto.saas.module;

import java.util.List;

/**
 * One module as the super admin panel renders it: what the plan says, what this shop actually
 * gets, and whether that difference is an explicit override.
 *
 * @param source PLAN when the value comes from the plan template, TENANT when this shop has an
 *               override row, LOCKED when the module can never be switched off
 */
public record ModuleNodeResponse(
        String key,
        String parentKey,
        String name,
        String description,
        String category,
        String categoryLabel,
        String icon,
        boolean locked,
        boolean enabled,
        boolean planEnabled,
        boolean overridden,
        String source,
        String overrideNote,
        List<ModuleNodeResponse> children
) {
    public static final String SOURCE_PLAN = "PLAN";
    public static final String SOURCE_TENANT = "TENANT";
    public static final String SOURCE_LOCKED = "LOCKED";
}
