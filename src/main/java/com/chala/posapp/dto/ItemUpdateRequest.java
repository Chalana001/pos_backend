package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ItemUpdateRequest {

    @Size(min = 2, max = 160)
    private String name;

    @Size(max = 80)
    private String category;

    @PositiveOrZero
    private Double costPrice;

    @PositiveOrZero
    private Double sellingPrice;

    @Min(0)
    private Integer reorderLevel;

    @Size(max = 500)
    private String imageUrl;

    private Boolean active;
}
