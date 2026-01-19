package com.chala.posapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CancelTransferRequest {
    @NotBlank
    private String reason;
}
