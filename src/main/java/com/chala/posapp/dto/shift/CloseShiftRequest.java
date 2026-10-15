package com.chala.posapp.dto.shift;

import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

@Data
public class CloseShiftRequest {
    @Min(0)
    private double countedCash;

    private String note;

    @Valid
    private List<CashDenominationCount> denominationCounts;
}
