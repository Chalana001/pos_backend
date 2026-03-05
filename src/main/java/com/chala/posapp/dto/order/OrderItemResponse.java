package com.chala.posapp.dto.order;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private Long batchId;
    private int qty;
    private double unitPrice;
    private String discountType;
    private double discountValue;
    private double finalUnitPrice;
    private double lineTotal;
}
