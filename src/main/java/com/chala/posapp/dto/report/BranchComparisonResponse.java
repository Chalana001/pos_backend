package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class BranchComparisonResponse {
    private Long branchId;
    private String branchName;
    private long orderCount;
    private double totalSales;
    private double averageOrderValue;
    private double totalDiscounts;
    private long returnCount;
    private double returnAmount;
    private double operatingExpenses;
}
