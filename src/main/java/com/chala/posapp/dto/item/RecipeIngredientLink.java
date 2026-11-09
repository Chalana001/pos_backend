package com.chala.posapp.dto.item;

/**
 * One recipe → ingredient relationship row, produced by the bulk retrieval
 * query in {@code RecipeIngredientRepository} ({@code findLinksForRecipes}).
 *
 * <p>Scalar projection of the {@code recipe_ingredients} columns the response
 * mapping consumes: the parent recipe item id, the ingredient item id and the
 * stored normalized base quantity (the table's {@code quantity} column —
 * grams for WEIGHT, milliliters for VOLUME, base units for NORMAL, as
 * {@code QuantityConversionUtil.normalizeQuantity} wrote it at recipe
 * creation time). The table stores plain id columns (no item association),
 * so the query needs no join and none is added.
 *
 * <p>{@code baseQuantity} is carried under the response's name for the same
 * value ({@code ItemIngredientResponse.baseQuantity}) to keep the mapping
 * 1:1 with the existing contract.
 */
public record RecipeIngredientLink(Long parentItemId, Long ingredientId, Integer baseQuantity) {
}
