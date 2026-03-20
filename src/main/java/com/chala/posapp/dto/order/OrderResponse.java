package com.chala.posapp.dto.order;

import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderResponse {
    private Long id;
    private String invoiceNo;
    private Long branchId;
    private Long cashierUserId;
    private Long customerId;
    private String customerName;
    private OrderType orderType;
    private OrderStatus status;

    private double subTotal;
    private double billDiscount;
    private double grandTotal;
    private double paidAmount;
    private double dueAmount;

    private String note;

    private LocalDateTime createdAt;

    private List<OrderItemResponse> items;
}
