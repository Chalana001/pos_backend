package com.chala.posapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashDropResponse {

    private Long id;
    private Long shiftId;
    private Long branchId;

    private Long cashierUserId;
    private String cashierName;

    private double amount;
    private String reason;
    private LocalDateTime createdAt;

}