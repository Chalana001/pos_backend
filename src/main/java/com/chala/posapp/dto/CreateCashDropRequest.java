package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCashDropRequest {

    @Min(1)
    private double amount;

    @NotBlank
    private String reason;
}
