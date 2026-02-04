package com.chala.posapp.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class GrnItemRequest {
    private Long itemId;
    private Integer qty;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private LocalDate expiryDate;
}