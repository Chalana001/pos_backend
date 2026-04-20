package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManualRenewRequest {
    @Min(1)
    @Max(12)
    private int cycles = 1;

    @PositiveOrZero
    private Double amountPaid;

    @Size(max = 255)
    private String note;
}
