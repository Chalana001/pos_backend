package com.chala.posapp.dto.returns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateReturnRequest {

    // Why the customer is returning — mandatory for audit trail
    @NotBlank(message = "reason is required")
    private String reason;

    // How the refund will be given: CASH / BANK / CARD / STORE_CREDIT
    @NotBlank(message = "refundMethod is required")
    private String refundMethod;

    // Optional internal note from cashier (not printed on receipt)
    private String cashierNote;

    // At least one item must be selected
    @NotEmpty(message = "At least one item must be selected for return")
    @Valid
    private List<ReturnItemRequest> items;
}
