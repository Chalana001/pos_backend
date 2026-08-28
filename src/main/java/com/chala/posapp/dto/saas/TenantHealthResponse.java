package com.chala.posapp.dto.saas;

import java.time.LocalDateTime;

/**
 * Operational state of one shop's database, for the panel's health view.
 *
 * <p>Every count is read straight from the tenant catalog rather than through JPA, so a shop
 * whose schema is behind or broken reports numbers instead of throwing. {@code reachable} is
 * false when the query failed — {@code error} then carries why.
 */
public record TenantHealthResponse(
        String tenantId,
        String shopName,
        String dbName,
        String migrationStatus,
        LocalDateTime migratedAt,
        String schemaVersion,
        boolean reachable,
        String error,
        long branchCount,
        long userCount,
        long itemCount,
        long orderCount,
        LocalDateTime lastOrderAt,
        LocalDateTime lastLoginAt
) {
}
