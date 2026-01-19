package com.chala.posapp.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreditPaymentResponse {
    private Long id;
    private Long customerId;
    private Long branchId;
    private Long cashierUserId;
    private double amount;
    private String note;
    private LocalDateTime paidAt;
}
