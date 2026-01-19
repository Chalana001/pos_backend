package com.chala.posapp.dto.chart;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DailySalesResponse {
    private String date;   // yyyy-MM-dd
    private double sales;
}
