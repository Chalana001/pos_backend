package com.chala.posapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExpenseTypeRequest {

    @NotBlank
    private String name;

    private boolean countInProfitReport = true;

    private boolean active = true;
}
