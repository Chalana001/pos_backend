package com.chala.posapp.dto.shift;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class OpenShiftRequest {
    @Min(0)
    private double openingCash;
    private Long assignedCashierId;
    private String note;
}
