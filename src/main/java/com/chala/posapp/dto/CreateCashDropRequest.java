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

    // Optional — which bank account this cash went to. Null means it's not
    // been banked yet (e.g. going to a safe).
    private Long bankAccountId;
}
