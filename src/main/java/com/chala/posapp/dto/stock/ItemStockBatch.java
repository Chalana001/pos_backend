package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.stock.StockBatchSourceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One positive stock batch row of one item, produced by the bulk retrieval
 * queries in {@code StockBatchRepository}
 * ({@code findAvailableBatchesForBranch} / {@code findAvailableBatchesAcrossBranches}).
 *
 * <p>Raw batch information only — the scalar columns the legacy
 * {@code ItemService.batchesToResponse} reads from the {@code StockBatch}
 * entity plus the item id a later assembler groups by. Everything else the
 * POS batch DTO carries is derived from the <em>item</em>, not from the batch:
 * <ul>
 *   <li>{@code displayQty} = {@code QuantityConversionUtil.toDisplayQuantity(item, quantity)};</li>
 *   <li>{@code displayUnit} = the item's {@code defaultUnit}.</li>
 * </ul>
 * Those stay with the existing shared conversion logic and the already-loaded
 * candidate items in the assembler; the projection deliberately holds no
 * unit-conversion fields and forces no join to the item entity.
 *
 * <p>{@code sourceType} and {@code receivedAt} are carried because they are
 * the ordering keys of the legacy query (PROCESSING first, then received
 * time, then id) and are part of the equivalence comparison against
 * {@code findAvailableBatchesForScope}. {@code quantity} is the raw base
 * quantity before any display conversion.
 */
public record ItemStockBatch(
        Long itemId,
        Long batchId,
        BigDecimal sellingPrice,
        Integer quantity,
        LocalDateTime expireDate,
        StockBatchSourceType sourceType,
        LocalDateTime receivedAt) {
}
