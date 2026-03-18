package com.chala.posapp.dto;

import com.chala.posapp.entity.ExpenseCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateExpenseRequest {

    @NotNull
    private ExpenseCategory category;

    @Min(1)
    private double amount;

    @NotNull
    private Long branchId;

    private boolean isFromDrawer=true;

    @NotBlank
    private String description;
}
