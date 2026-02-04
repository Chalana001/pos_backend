package com.chala.posapp.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ItemCreateRequest {
    @NotBlank
    private String barcode;

    @NotBlank
    private String name;

    @NotNull(message = "Sub category is required")
    private Long subCategoryId;

    @PositiveOrZero
    private BigDecimal costPrice;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    @Min(0)
    private int reorderLevel;

    private String imageUrl;
    private Boolean active;
}
