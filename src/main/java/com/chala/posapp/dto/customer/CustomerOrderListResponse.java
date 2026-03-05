package com.chala.posapp.dto.customer;

import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrderListResponse {
    private Long id;
    private String invoiceNo;
    private Long branchId;
    private OrderType orderType;
    private OrderStatus status;

    private double grandTotal;
    private double paidAmount;
    private double dueAmount;

    private LocalDateTime createdAt;
}
