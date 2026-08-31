package com.chala.posapp.dto.returns;

import com.chala.posapp.entity.ReturnStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderReturnResponse {

    private Long id;

    // e.g. RTN-2026-06-B1-000001
    private String returnNo;

    private Long originalOrderId;
    private String originalInvoiceNo;

    private Long branchId;
    private String cashierName;

    private Long customerId;
    private String customerName;

    private ReturnStatus status;

    // CASH / BANK / CARD / STORE_CREDIT
    private String refundMethod;

    private double totalRefundAmount;

    private String reason;
    private String cashierNote;

    private LocalDateTime createdAt;

    private List<OrderReturnItemResponse> items;
}
