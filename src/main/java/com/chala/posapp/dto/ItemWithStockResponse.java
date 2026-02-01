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

    // String category wenuwata me details tika danna
    private String categoryName;    // e.g., "Computer Parts"
    private String subCategoryName; // e.g., "Processors"
    private Long subCategoryId;

    // Use BigDecimal for money consistency
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private int reorderLevel;
    private boolean active;
    private LocalDateTime createdAt;

    private int quantity; // Branch stock qty
}