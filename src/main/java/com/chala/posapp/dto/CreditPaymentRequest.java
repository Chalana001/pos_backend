package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreditPaymentRequest {

    @NotNull
    private Long customerId;

    @Min(1)
    private double amount;

    private String note;
}
