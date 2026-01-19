package com.chala.posapp.dto.report;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CreditDueResponse {
    private Long customerId;
    private String customerName;
    private double dueAmount;
}
