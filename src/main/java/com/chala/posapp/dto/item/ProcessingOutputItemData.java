package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

import java.math.BigDecimal;

/**
 * The output item fields the processing-output mapping and quantity
 * conversion actually read, loaded in bulk by
 * {@code ItemRepository.findProcessingOutputItemDataByIdIn}.
 *
 * <p>Field set verified against source, not guessed:
 * {@code ItemService.mapProcessingOutputResponses} reads id, barcode, name,
 * itemType, defaultUnit and sellingPrice (the price-fallback branch), and
 * {@code QuantityConversionUtil}'s two legacy calls read exactly itemType and
 * defaultUnit — {@code toDisplayQuantity(item, qty)} delegates to
 * {@code toDisplayQuantity(itemType, defaultUnit, qty)} and
 * {@code primaryDisplayUnit(item)} branches on itemType and returns
 * defaultUnit otherwise. No other {@code Item} field participates. There is
 * no separate "default quantity unit" column on Item — the response's
 * {@code defaultQtyUnit} is derived from these fields, not stored.
 *
 * <p>The query is a JPQL constructor expression on {@code Item}, so
 * {@code @SQLRestriction("deleted_at IS NULL")} applies exactly as it does to
 * the legacy {@code findAllById} lookup: soft-deleted output items are
 * invisible here too and therefore read as missing.
 */
public record ProcessingOutputItemData(
        Long itemId,
        String barcode,
        String name,
        ItemType itemType,
        MeasurementUnit defaultUnit,
        BigDecimal sellingPrice) {
}
