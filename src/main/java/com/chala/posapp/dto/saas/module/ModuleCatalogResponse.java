package com.chala.posapp.dto.saas.module;

import java.util.List;

/**
 * The catalog itself, independent of any shop — used by the panel's Modules page to show what
 * can be sold, and how many shops currently have each module switched on.
 */
public record ModuleCatalogResponse(
        List<CategoryGroup> categories,
        int totalModules
) {
    public record CategoryGroup(
            String category,
            String label,
            List<Entry> modules
    ) {
    }

    /**
     * @param overrideCount how many shops deviate from their plan on this module — a high number
     *                      usually means the plan templates are wrong, not the shops
     */
    public record Entry(
            String key,
            String parentKey,
            String name,
            String description,
            String icon,
            boolean locked,
            long overrideCount,
            List<String> apiRoutes,
            List<String> uiPaths,
            List<Entry> children
    ) {
    }
}
