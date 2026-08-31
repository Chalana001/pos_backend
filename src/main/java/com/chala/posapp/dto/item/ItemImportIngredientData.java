package com.chala.posapp.dto.item;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemImportIngredientData {
    private int rowNumber;
    private Long recipeItemId;
    private String recipeImportKey;
    private String recipeBarcode;
    private String recipeName;
    private String ingredient;
    private String ingredientImportKey;
    private Long ingredientItemId;
    private String ingredientBarcode;
    private String ingredientName;
    private BigDecimal quantity;
    private MeasurementUnit qtyUnit;
    private ItemImportRowStatus status;
    private String message;
}
