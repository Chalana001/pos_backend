package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPerformanceResponse {
    private Long customerId;
    private String customerName;
    private String phone;
    private long orderCount;
    private double totalSpent;
    private double totalPaid;
    private double totalDue;
    private double averageOrderValue;
    private LocalDateTime lastOrderAt;
}
