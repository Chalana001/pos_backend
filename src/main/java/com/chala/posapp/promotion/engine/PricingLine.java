package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.Category;
import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.SubCategory;

import java.math.BigDecimal;

/**
 * One cart line, with everything the engine needs to price it already resolved.
 *
 * <p>The category chain is walked once here, at construction, rather than inside the match
 * loop. Both associations on {@code Item} are lazy, so the old shape — dereferencing them per
 * promotion per line — touched the proxies on the checkout hot path; this touches them once and
 * hands the engine plain ids.
 */
public record PricingLine(
        Long itemId,
        ItemType itemType,
        Long subCategoryId,
        Long categoryId,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        int normalizedQty,
        DiscountType manualDiscountType,
        BigDecimal manualDiscountValue
) {

    /** The same line at a different unit price — how a line priced after its own discounts is handed to the bill pass. */
    public PricingLine withUnitPrice(BigDecimal newUnitPrice) {
        return new PricingLine(itemId, itemType, subCategoryId, categoryId, newUnitPrice, costPrice,
                normalizedQty, DiscountType.NONE, BigDecimal.ZERO);
    }

    public static PricingLine from(Item item, double unitPrice, int normalizedQty,
                                   DiscountType manualType, double manualValue) {
        SubCategory subCategory = item.getSubCategory();
        Category category = subCategory != null ? subCategory.getCategory() : null;
        return new PricingLine(
                item.getId(),
                item.getItemType(),
                subCategory != null ? subCategory.getId() : null,
                category != null ? category.getId() : null,
                BigDecimal.valueOf(unitPrice),
                item.getCostPrice(),
                normalizedQty,
                manualType == null ? DiscountType.NONE : manualType,
                BigDecimal.valueOf(manualValue)
        );
    }
}
