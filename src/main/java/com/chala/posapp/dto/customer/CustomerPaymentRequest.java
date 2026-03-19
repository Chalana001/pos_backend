package com.chala.posapp.dto.customer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CustomerPaymentRequest {

    @NotNull(message = "Payment amount is required")
    @Min(value = 1, message = "Amount must be greater than 0")
    private Double amount;

    private String paymentMethod; // CASH, CARD, BANK_TRANSFER

    private String note; // Optional note
}