package com.chala.posapp.dto.report;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ProfitReportResponse {
    private Long itemId;
    private String itemName;
    private long qtySold;
    private double revenue;
    private double cost;
    private double profit;
}
