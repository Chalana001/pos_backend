package com.chala.posapp.dto.chart;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MonthlySalesResponse {
    private String month;
    private double sales;
}
