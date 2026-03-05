package com.chala.posapp.dto.order;

import com.chala.posapp.entity.OrderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    private Long branchId;

    @NotNull
    private OrderType orderType; // CASH or CREDIT

    // required if CREDIT
    private Long customerId;

    @Valid
    @NotNull
    private List<OrderItemRequest> items;

    @Min(0)
    private double billDiscount;

    @Min(0)
    private double paidAmount; // for CASH

    private String note;
}
