package com.chala.posapp.dto.shift;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class ShiftCashCountSettingsRequest {
    private boolean enabled;

    @NotEmpty
    private List<@Min(1) @Max(1000000) Integer> denominations;
}
