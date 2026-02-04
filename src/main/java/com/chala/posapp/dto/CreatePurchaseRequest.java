package com.chala.posapp.dto;

import lombok.Data;
import java.util.List;

@Data
public class CreatePurchaseRequest {
    private Long supplierId;
    private String invoiceNo;
    private List<BranchPurchaseRequest> branches;
}