package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CategorySalesResponse {
    private String categoryName;
    private double totalSales;
}
