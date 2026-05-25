package com.chala.posapp.dto.report;

import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.SaleMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportResponse {
    private Long orderId;
    private String invoiceNo;
    private Long branchId;
    private String branchName;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private Long cashierUserId;
    private String cashierName;
    private OrderType orderType;
    private String paymentMethod;
    private SaleMode saleMode;
    private OrderStatus status;
    private double subTotal;
    private double discount;
    private double grandTotal;
    private double paidAmount;
    private double dueAmount;
    private LocalDateTime createdAt;
}
