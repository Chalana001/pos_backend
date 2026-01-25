package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal; // Import this

@Data
public class ItemUpdateRequest {

    @Size(min = 2, max = 160)
    private String name;

    @Size(max = 80)
    private String category;

    // Double වෙනුවට BigDecimal දාන්න
    @PositiveOrZero
    private BigDecimal costPrice;

    @PositiveOrZero
    private BigDecimal sellingPrice;

    @Min(0)
    private Integer reorderLevel;

    @Size(max = 500)
    private String imageUrl;

    private Boolean active;
}