package com.chala.posapp.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class GrnItemRequest {
    private Long itemId;
    private Integer qty;

    // Buying Price (Cost Per Unit)
    private BigDecimal costPrice;

    // Selling Price (New Price for this batch)
    private BigDecimal sellingPrice;

    // Optional: කල් ඉකුත් වන දිනය (Food/Medicine වලට වැදගත්)
    private LocalDate expiryDate;
}