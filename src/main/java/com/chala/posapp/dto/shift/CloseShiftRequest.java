package com.chala.posapp.dto.shift;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class CloseShiftRequest {
    @Min(0)
    private double countedCash;

    private String note;
}
