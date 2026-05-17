package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyPeriodUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class WarrantyTemplateRequest {
    @NotBlank
    private String label;

    @Positive
    private int periodValue;

    @NotNull
    private WarrantyPeriodUnit periodUnit;

    private boolean active = true;
}
