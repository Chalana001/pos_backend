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
public class ExpenseTypeResponse {
    private Long id;
    private String name;
    private boolean countInProfitReport;
    private boolean active;
    private long usageCount;
    private LocalDateTime createdAt;
}
