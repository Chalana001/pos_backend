package com.chala.posapp.dto.saas;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShopPaymentResponse {
    private Long id;
    private String actionType;
    private double amount;
    private String note;
    private String performedBy;
    private LocalDateTime createdAt;
}
