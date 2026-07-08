package com.chala.posapp.dto.stock;

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
public class StockItemDetailsResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private String altName;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private Integer reorderLevel;
    private Long categoryId;
    private String categoryName;
    private Long subCategoryId;
    private String subCategoryName;
    private Integer totalQuantity;
    private BigDecimal displayQuantity;
    private List<StockItemBatchDetailsResponse> activeBatches;
}
