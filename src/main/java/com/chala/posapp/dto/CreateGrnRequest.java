package com.chala.posapp.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateGrnRequest {
    private Long branchId;
    private Long supplierId;

    private BigDecimal totalAmount;
    private BigDecimal paidAmount;

    private String note;
    private List<GrnItemRequest> items;
}
