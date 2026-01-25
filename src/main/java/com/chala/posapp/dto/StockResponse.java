package com.chala.posapp.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class StockResponse {
    private Long id;            // Batch ID
    private Long branchId;
    private Long itemId;
    private String batchCode;   // New: Batch Code eka
    private Integer quantity;   // Current Qty

    private BigDecimal costPrice;    // New: Cost
    private BigDecimal sellingPrice; // New: Selling Price

    private LocalDateTime receivedAt; // updatedAt wenuwata receivedAt
    private LocalDateTime expireDate; // Optional
}
