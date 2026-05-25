package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemImportRowData {
    private int rowNumber;
    private Boolean selected;
    private Long existingItemId;
    private Boolean recipeIngredientOnly;
    private String importKey;
    private String barcode;
    private String name;
    private String enteredMainCategory;
    private Long categoryId;
    private String categoryName;
    private String enteredSubCategory;
    private Long subCategoryId;
    private String subCategoryName;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal reorderLevel;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private Boolean active;
    private Boolean posVisible;
    private Boolean kotEnabled;
    private List<Long> branchIds;
    private List<ItemImportIngredientData> ingredients;
    private ItemImportRowStatus status;
    private String message;
}
