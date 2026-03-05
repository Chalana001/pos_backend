package com.chala.posapp.dto.branch;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignBranchRequest {
    @NotNull
    private Long branchId;
}
