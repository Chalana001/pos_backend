package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

import java.math.BigDecimal;

/**
 * The purchase form's item-search contract. Deliberately excludes the nested
 * recipe, processing, branch-assignment, and positive-batch data carried by
 * the general {@link ItemResponse}.
 */
public record PurchaseItemSearchResponse(
        Long id,
        String name,
        String altName,
        String barcode,
        ItemType itemType,
        BigDecimal costPrice,
        BigDecimal sellingPrice,
        MeasurementUnit defaultUnit,
        BigDecimal availableQty,
        Integer availableBaseQty,
        boolean hasNegativeStock) {
}
