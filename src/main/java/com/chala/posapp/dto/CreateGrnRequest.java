package com.chala.posapp.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateGrnRequest {
    private Long branchId;
    private Long supplierId;

    private BigDecimal totalAmount; // Frontend එකෙන් එවන Total එක (Validation සඳහා)
    private BigDecimal paidAmount;  // එවලේම සල්ලි දුන්නා නම්

    private String note;
    private List<GrnItemRequest> items;
}
