package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePackageRequest {
    @NotNull
    @Positive
    private Long planId;

    @PositiveOrZero
    private Double amountPaid;

    @Size(max = 255)
    private String note;
}
