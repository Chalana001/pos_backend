package com.chala.posapp.dto.order;

import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.SaleMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OfflineSaleImportRequest {

    @NotBlank
    private String clientSaleId;

    private LocalDateTime offlineSoldAt;

    private Long branchId;

    @NotNull
    private OrderType orderType;

    private SaleMode saleMode;

    private Long tableId;

    private Long customerId;

    @Valid
    @NotNull
    private List<OrderItemRequest> items;

    @Min(0)
    private double billDiscount;

    @Min(0)
    private double paidAmount;

    private String paymentMethod;

    private String note;
}
