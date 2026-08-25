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

    private Long bankAccountId;
    private String bankAccountName;

    // True when this drop was recorded outside any shift (shiftId is null) —
    // pure record-keeping, doesn't reduce any shift's Expected Cash.
    private boolean outsideShift;

    private LocalDateTime createdAt;

}