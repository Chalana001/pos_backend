package com.chala.posapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashDropSummaryResponse {
    private double totalAmount;
    private long dropCount;
    private double averageAmount;
}
