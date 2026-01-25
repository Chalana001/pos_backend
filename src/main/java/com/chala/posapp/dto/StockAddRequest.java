package com.chala.posapp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StockAddRequest {
    private Long branchId;
    private Long itemId;
    private Integer quantity;

    // අලුත් Fields (Batch එකට ඕන නිසා)
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private String batchCode; // GRN Number එක (Optional)
    private Long supplierId;  // Optional
    private LocalDateTime expireDate; // Optional
}