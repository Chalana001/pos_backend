package com.chala.posapp.dto.item;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.chala.posapp.dto.stock.StockBatchResponse;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.ItemOverheadCostMode;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ItemResponse {
    private Long id;
    private String barcode;
    private String name;

    private Long categoryId;
    private String categoryName;
    private Long subCategoryId;
    private String subCategoryName;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal availableQty;
    private Integer availableBaseQty;
    private BigDecimal reorderLevel;
    private Integer reorderLevelBaseQty;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private String imageUrl;

    @JsonProperty("isKotEnabled")
    private boolean kotEnabled;

    private boolean active;
    private boolean posVisible;
    private boolean stockProcessingEnabled;
    private ItemOverheadCostMode overheadCostMode;
    private BigDecimal overheadCostValue;
    private LocalDateTime createdAt;
    private List<Long> branchIds;
    private List<ItemIngredientResponse> ingredients;
    private List<StockProcessingOutputLinkResponse> processingOutputs;
    private List<StockBatchResponse> batches;
}
