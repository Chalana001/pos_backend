package com.chala.posapp.dto;

import lombok.Data;
import java.util.List;

@Data
public class BranchPurchaseRequest {
    private Long branchId;
    private List<GrnItemRequest> items;
}