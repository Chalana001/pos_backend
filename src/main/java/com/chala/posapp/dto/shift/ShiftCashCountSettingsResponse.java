package com.chala.posapp.dto.shift;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftCashCountSettingsResponse {
    private boolean enabled;
    private List<Integer> denominations;
}
