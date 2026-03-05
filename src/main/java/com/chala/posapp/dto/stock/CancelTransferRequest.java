package com.chala.posapp.dto.stock;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CancelTransferRequest {
    @NotBlank
    private String reason;
}
