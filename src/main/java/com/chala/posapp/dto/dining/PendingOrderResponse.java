package com.chala.posapp.dto.dining;

import com.chala.posapp.entity.SaleMode;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PendingOrderResponse {
    private Long id;
    private Long branchId;
    private Long tableId;
    private String tableName;
    private Long cashierUserId;
    private Long customerId;
    private String customerName;
    private SaleMode saleMode;
    private double subTotal;
    private double billDiscount;
    private double grandTotal;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PendingOrderItemResponse> items;
}
