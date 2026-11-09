package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

import java.math.BigDecimal;

/** The narrow item contract consumed by promotion item search and category import. */
public record PromotionItemSearchResponse(
        Long id,
        String name,
        String altName,
        String barcode,
        Long categoryId,
        String categoryName,
        Long subCategoryId,
        String subCategoryName,
        BigDecimal costPrice,
        BigDecimal sellingPrice,
        ItemType itemType,
        MeasurementUnit defaultUnit) {
}
