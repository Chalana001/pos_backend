package com.chala.posapp.dto;

import lombok.Data;
import java.util.List;

@Data
public class CreatePurchaseRequest {
    private Long supplierId;
    private String invoiceNo; // INV-999
    private List<BranchPurchaseRequest> branches;
}