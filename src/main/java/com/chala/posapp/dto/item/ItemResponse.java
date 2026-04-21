package com.chala.posapp.dto.item;

import com.chala.posapp.dto.stock.StockBatchResponse;
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
    private boolean weightItem;
    private MeasurementUnit defaultUnit;
    private String imageUrl;
    private boolean active;
    private LocalDateTime createdAt;
    private List<StockBatchResponse> batches;
}
