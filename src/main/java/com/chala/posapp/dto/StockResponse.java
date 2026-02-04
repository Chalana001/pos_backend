package com.chala.posapp.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor

public class StockResponse {
    private Long id;
    private Long branchId;
    private Long itemId;
    private String batchCode;
    private Integer quantity;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private LocalDateTime receivedAt;
    private LocalDateTime expireDate;
}
