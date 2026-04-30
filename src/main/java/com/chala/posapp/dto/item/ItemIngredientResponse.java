package com.chala.posapp.dto.item;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ItemIngredientResponse {
    private Long ingredientItemId;
    private String ingredientBarcode;
    private String ingredientName;
    private BigDecimal quantity;
    private Integer baseQuantity;
    private MeasurementUnit qtyUnit;
}
