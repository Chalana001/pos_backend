package com.chala.posapp.dto.item;

import com.chala.posapp.entity.MeasurementUnit;

import java.math.BigDecimal;

/**
 * One recipe ingredient line, carrying exactly the fields of the existing
 * {@link ItemIngredientResponse} contract: the ingredient item's current id,
 * barcode and name, the stored normalized {@code baseQuantity}, the display
 * {@code quantity} converted with the ingredient item's current type and
 * default unit, and {@code qtyUnit} = the ingredient item's current
 * {@code defaultUnit} (NOT the unit requested at recipe creation time).
 *
 * <p>Immutable value object — the reader returns these instead of JPA
 * entities or the mutable response DTO. {@link #toResponse()} builds the
 * existing public DTO field-for-field for the future assembler, so the
 * response contract is preserved without changing it.
 */
public record RecipeIngredientValue(
        Long ingredientItemId,
        String ingredientBarcode,
        String ingredientName,
        BigDecimal quantity,
        Integer baseQuantity,
        MeasurementUnit qtyUnit) {

    /** The existing response DTO, populated with this value's fields. */
    public ItemIngredientResponse toResponse() {
        return ItemIngredientResponse.builder()
                .ingredientItemId(ingredientItemId)
                .ingredientBarcode(ingredientBarcode)
                .ingredientName(ingredientName)
                .quantity(quantity)
                .baseQuantity(baseQuantity)
                .qtyUnit(qtyUnit)
                .build();
    }
}
