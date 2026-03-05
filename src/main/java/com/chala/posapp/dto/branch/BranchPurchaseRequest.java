package com.chala.posapp.dto.branch;

import com.chala.posapp.dto.grn.GrnItemRequest;
import lombok.Data;
import java.util.List;

@Data
public class BranchPurchaseRequest {
    private Long branchId;
    private List<GrnItemRequest> items;
}