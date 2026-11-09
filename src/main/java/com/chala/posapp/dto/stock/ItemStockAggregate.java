package com.chala.posapp.dto.stock;

/**
 * One item's stock aggregate over a batch scope, produced by the bulk
 * aggregation queries in {@code StockBatchRepository}
 * ({@code aggregateStockForBranch} / {@code aggregateStockAcrossBranches}).
 *
 * <p>Raw aggregation only — this type deliberately does NOT decide POS
 * eligibility. A caller interprets:
 * <ul>
 *   <li>{@code batchCount > 0} — historical batch existence, the same
 *       any-row rule {@code existsBatchForScope} applies (zero-quantity rows
 *       included);</li>
 *   <li>{@code negativeBatchCount > 0} — negative-stock status, the same rule
 *       as {@code existsNegativeBatchForScope}, independent of the net;</li>
 *   <li>{@code totalQuantity} — the net over every applicable row, positive,
 *       zero and negative alike, the same sum as
 *       {@code getTotalQuantityByItemAndScope}.</li>
 * </ul>
 *
 * <p>An item with no batch rows produces no aggregate row at all (the query
 * groups existing rows). A caller must read the absence of a row as "never
 * stocked": net 0, batch count 0, negative count 0 — exactly what the legacy
 * per-item methods answer for such an item.
 *
 * <p>Types: {@code quantity} is an {@code Integer} column but SQL {@code SUM}
 * widens (BIGINT on H2, DECIMAL on MySQL), and a SUM over many int rows can
 * exceed {@code Integer.MAX_VALUE}. Every aggregate is therefore carried as
 * {@code Long}; nothing is cast narrow and no precision is lost.
 */
public record ItemStockAggregate(
        Long itemId,
        Long totalQuantity,
        Long batchCount,
        Long negativeBatchCount) {
}
