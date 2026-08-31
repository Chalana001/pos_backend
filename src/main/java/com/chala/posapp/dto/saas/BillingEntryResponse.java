package com.chala.posapp.dto.saas;

import java.time.LocalDateTime;

/**
 * One line of the billing ledger. Unlike {@link ShopPaymentResponse}, which is scoped to a shop
 * already on screen, this carries the shop identity so the global ledger can be filtered and
 * exported.
 */
public record BillingEntryResponse(
        Long id,
        String tenantId,
        String shopName,
        String actionType,
        double amount,
        String note,
        String performedBy,
        LocalDateTime createdAt
) {
}
