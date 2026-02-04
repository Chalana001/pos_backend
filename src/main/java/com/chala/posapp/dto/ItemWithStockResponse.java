package com.chala.posapp.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ItemWithStockResponse {
    private Long id;
    private String barcode;
    private String name;

    private String categoryName;
    private String subCategoryName;
    private Long subCategoryId;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private int reorderLevel;
    private boolean active;
    private LocalDateTime createdAt;

    private int quantity;
}