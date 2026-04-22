package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ItemCreateRequest {

    private String barcode;

    @NotBlank
    private String name;

    @NotNull(message = "Sub category is required")
    private Long subCategoryId;

    @PositiveOrZero
    private BigDecimal costPrice;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    @PositiveOrZero
    private BigDecimal reorderLevel;

    private ItemType itemType;

    private MeasurementUnit defaultUnit;
    private List<Long> branchIds;

    private String imageUrl;
    private Boolean active;
}
