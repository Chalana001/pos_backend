package com.chala.posapp.dto.payments;

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
    private String paymentMethod;
    private String note;
    private LocalDateTime paidAt;
}
