package com.chala.posapp.dto.dining;

import com.chala.posapp.dto.order.OrderItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PendingOrderSaveRequest {

    private Long customerId;

    @Valid
    @NotNull
    private List<OrderItemRequest> items;

    @Min(0)
    private double billDiscount;

    private String note;
}
