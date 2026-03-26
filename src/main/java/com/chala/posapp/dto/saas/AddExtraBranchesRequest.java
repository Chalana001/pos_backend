package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddExtraBranchesRequest {
    @Min(1)
    private int extraBranches;

    @PositiveOrZero
    private Double amountPaid;

    @Size(max = 255)
    private String note;
}
