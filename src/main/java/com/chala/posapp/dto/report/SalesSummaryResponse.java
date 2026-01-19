package com.chala.posapp.dto.report;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SalesSummaryResponse {
    private double totalSales;
    private double cashSales;
    private double creditSales;
    private double totalDiscount;
    private long totalOrders;
}
