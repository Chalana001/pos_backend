package com.chala.posapp.dto.item;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ItemIngredientRequest {

    @NotNull
    private Long ingredientItemId;

    @NotNull
    @Positive
    private BigDecimal quantity;

    private MeasurementUnit qtyUnit;
}
