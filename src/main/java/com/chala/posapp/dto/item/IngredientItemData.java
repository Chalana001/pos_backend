package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

/**
 * The ingredient item fields the recipe ingredient mapping and quantity
 * conversion actually read, loaded in bulk by
 * {@code ItemRepository.findIngredientDataByIds}.
 *
 * <p>Field set verified against source, not guessed:
 * {@code ItemService.mapIngredientResponse} reads {@code id}, {@code barcode},
 * {@code name} and {@code defaultUnit}, and
 * {@code QuantityConversionUtil.toDisplayQuantity(itemType, defaultUnit, qty)}
 * reads exactly {@code itemType} and {@code defaultUnit} (the
 * {@code toDisplayQuantity(Item, …)} overload is a one-line delegation to
 * that overload). No other {@code Item} field participates, so the projection
 * loads nothing else.
 *
 * <p>The query is a JPQL constructor expression on {@code Item}, so
 * {@code @SQLRestriction("deleted_at IS NULL")} applies exactly as it does to
 * the legacy {@code findAllById} lookup: soft-deleted ingredient items are
 * invisible here too and read as missing.
 */
public record IngredientItemData(
        Long itemId,
        String barcode,
        String name,
        ItemType itemType,
        MeasurementUnit defaultUnit) {
}
