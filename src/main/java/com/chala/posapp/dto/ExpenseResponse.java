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
    private String category;
    private String description;
    private Long branchId;
    private String branchName;  // 🔥 මේක අනිවාර්යයෙන්ම ඕනේ
    private Long shiftId;
    private Long cashierId;
    private String cashierName; // 🔥 මේක අනිවාර්යයෙන්ම ඕනේ
    private LocalDateTime createdAt;
}