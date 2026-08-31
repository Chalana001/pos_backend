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
public class ExpenseResponse {
    private Long id;
    private Double amount;
    private Long expenseTypeId;
    private String category;
    private boolean countInProfitReport;
    private String description;
    private Long branchId;
    private String branchName;
    private Long shiftId;
    private Long cashierId;
    private String cashierName;
    private LocalDateTime createdAt;
}
