package com.chala.posapp.dto;

import com.chala.posapp.dto.branch.BranchPurchaseRequest;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreatePurchaseRequest {
    private Long supplierId;
    private String invoiceNo;
    private BigDecimal discountAmount;
    private BigDecimal paidAmount;
    private String paymentMethod;
    private List<BranchPurchaseRequest> branches;
}
