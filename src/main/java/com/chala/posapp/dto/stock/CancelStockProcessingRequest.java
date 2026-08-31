package com.chala.posapp.dto.stock;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CancelStockProcessingRequest {
    @NotBlank
    private String reason;
}
