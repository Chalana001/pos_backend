package com.chala.posapp.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ItemCreateRequest {

    @NotBlank
    @Size(min = 1, max = 80)
    private String barcode;

    @NotBlank
    @Size(min = 2, max = 160)
    private String name;

    @Size(max = 80)
    private String category;

    @PositiveOrZero
    private double costPrice;

    @PositiveOrZero
    private double sellingPrice;

    @Min(0)
    private int reorderLevel;

    // optional image url (we will control via setting later)
    @Size(max = 500)
    private String imageUrl;
}
