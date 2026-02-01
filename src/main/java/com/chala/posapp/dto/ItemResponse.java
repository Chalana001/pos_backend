package com.chala.posapp.dto;

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
    private Double availableQty;
    private int reorderLevel;
    private String imageUrl;
    private boolean active;
    private LocalDateTime createdAt;
    private List<StockBatchResponse> batches;
}
