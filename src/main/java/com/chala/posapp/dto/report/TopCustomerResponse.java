package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class TopCustomerResponse {
    private Long customerId;
    private String customerName;
    private String phone;
    private long orderCount;
    private double totalSpent;
}