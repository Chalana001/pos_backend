package com.chala.posapp.dto.chart;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DailySalesResponse {
    private String date;
    private double sales;
}
