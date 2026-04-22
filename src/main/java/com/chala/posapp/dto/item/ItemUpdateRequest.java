package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

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

    private ItemType itemType;

    private MeasurementUnit defaultUnit;
    private List<Long> branchIds;

    @Size(max = 500)
    private String imageUrl;

    private Boolean active;
}
