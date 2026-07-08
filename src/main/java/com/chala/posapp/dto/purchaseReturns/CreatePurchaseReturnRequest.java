package com.chala.posapp.dto.purchaseReturns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreatePurchaseReturnRequest {

    @NotNull(message = "grnId is required")
    private Long grnId;

    @NotBlank(message = "reason is required")
    private String reason;

    private String note;

    @NotEmpty(message = "At least one item must be selected for return")
    @Valid
    private List<ReturnGrnItemRequest> items;
}
