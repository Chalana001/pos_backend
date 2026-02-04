package com.chala.posapp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StockAddRequest {
    private Long branchId;
    private Long itemId;
    private Integer quantity;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private String batchCode;
    private Long supplierId;
    private LocalDateTime expireDate;
}