package com.chala.posapp.dto.saas;

import java.util.List;
import java.util.Map;

/**
 * Platform-wide operational snapshot.
 *
 * @param schemaVersions      distinct tenant schema versions in use, and how many shops sit on
 *                            each — more than one entry means a partial rollout
 * @param unmappedApiRoutes   requests seen since boot that no module claims; each one is a route
 *                            that cannot currently be sold or switched off
 * @param inactiveModuleKeys  modules that still have plan or tenant rows but are gone from the
 *                            code catalog
 */
public record SystemHealthResponse(
        long totalShops,
        long activeShops,
        long expiredShops,
        long blockedShops,
        Map<String, Long> databaseStatusCounts,
        Map<String, Long> schemaVersions,
        int unreachableShops,
        List<String> unmappedApiRoutes,
        List<String> inactiveModuleKeys,
        int catalogModuleCount,
        String masterDatabase
) {
}
