package com.chala.posapp.dto.item;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ItemUpdateRequest {

    @Size(min = 2, max = 160)
    private String name;

    private Long subCategoryId;

    @PositiveOrZero
    private BigDecimal costPrice;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    @PositiveOrZero
    private BigDecimal reorderLevel;

    private Boolean weightItem;

    private MeasurementUnit defaultUnit;

    @Size(max = 500)
    private String imageUrl;

    private Boolean active;
}
