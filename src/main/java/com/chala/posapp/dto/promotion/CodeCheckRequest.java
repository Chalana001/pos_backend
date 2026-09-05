package com.chala.posapp.dto.promotion;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CodeCheckRequest {
    @NotBlank
    private String code;
    private Long customerId;
    private Long branchId;
}
