package com.chala.posapp.dto.order;

import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.SaleMode;
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
    private String branchName;
    private String branchAddress;
    private String branchPhone;
    private String branchLogo;
    private Long cashierUserId;
    private String cashierName;
    private Long customerId;
    private String customerName;
    private OrderType orderType;
    private String paymentMethod;
    private SaleMode saleMode;
    private Long tableId;
    private String tableName;
    private OrderStatus status;

    private double subTotal;
    private double billDiscount;
    private double promotionDiscountTotal;
    private Long billPromotionId;
    private String billPromotionName;
    private double billPromotionDiscountAmount;
    private double grandTotal;
    private double paidAmount;
    private double dueAmount;

    private String note;

    private LocalDateTime createdAt;

    private List<OrderItemResponse> items;
}
