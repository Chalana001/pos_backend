package com.chala.posapp.dto.item;

import java.math.BigDecimal;

/**
 * One stock-processing output relationship row, produced by the bulk
 * retrieval query in {@code StockProcessingOutputLinkRepository}
 * ({@code findProcessingOutputLinksForSources}).
 *
 * <p>Scalar projection of the {@code stock_processing_output_links} columns
 * the response mapping consumes:
 * <ul>
 *   <li>{@code linkId} — the relationship id, the legacy ordering key
 *       ({@code findBySourceItemIdOrderByIdAsc});</li>
 *   <li>{@code sourceItemId} / {@code outputItemId} — plain id columns, no
 *       item associations, so the query needs no join;</li>
 *   <li>{@code defaultQuantity} — the stored normalized base quantity
 *       (grams, milliliters or base units, as
 *       {@code QuantityConversionUtil.normalizeQuantity} wrote it);</li>
 *   <li>{@code defaultSellingPrice} — the nullable link-level price
 *       override (V5 column);</li>
 *   <li>{@code waste} — the nullable-safe waste flag; the column is
 *       {@code NOT NULL} so it is never null from the database, but the
 *       reader still applies the write path's {@code Boolean.TRUE.equals}
 *       semantics so an absent value maps to false exactly like
 *       {@code syncProcessingOutputs} stores it.</li>
 * </ul>
 */
public record ProcessingOutputLinkData(
        Long linkId,
        Long sourceItemId,
        Long outputItemId,
        Integer defaultQuantity,
        BigDecimal defaultSellingPrice,
        Boolean waste) {
}
