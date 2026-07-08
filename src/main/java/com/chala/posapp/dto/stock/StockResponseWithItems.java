package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockResponseWithItems {
    private Long itemId;
    private String barcode;
    private String itemName;
    private String altName;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private Long totalQuantity;
    private BigDecimal displayQuantity;
    private Integer reorderLevel;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private Long categoryId;
    private String categoryName;
    private Long subCategoryId;
    private String subCategoryName;

    public StockResponseWithItems(
            Long itemId,
            String barcode,
            String itemName,
            String altName,
            BigDecimal costPrice,
            BigDecimal sellingPrice,
            Long totalQuantity,
            Integer reorderLevel,
            ItemType itemType,
            MeasurementUnit defaultUnit,
            Long categoryId,
            String categoryName,
            Long subCategoryId,
            String subCategoryName
    ) {
        this.itemId = itemId;
        this.barcode = barcode;
        this.itemName = itemName;
        this.altName = altName;
        this.costPrice = costPrice;
        this.sellingPrice = sellingPrice;
        this.totalQuantity = totalQuantity;
        this.reorderLevel = reorderLevel;
        this.itemType = itemType;
        this.defaultUnit = defaultUnit;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.subCategoryId = subCategoryId;
        this.subCategoryName = subCategoryName;
    }
}
