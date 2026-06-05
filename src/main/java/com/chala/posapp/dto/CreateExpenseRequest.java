package com.chala.posapp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateExpenseRequest {

    @NotNull
    private Long expenseTypeId;

    @Min(1)
    private double amount;

    @NotNull
    private Long branchId;

    @JsonProperty("fromDrawer")
    @JsonAlias("isFromDrawer")
    private boolean fromDrawer;

    @NotBlank
    private String description;
}
