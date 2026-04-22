package com.chala.posapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CancelPurchaseRequest {
    @NotBlank
    private String reason;
}
