package com.chala.posapp.dto.dining;

import com.chala.posapp.entity.DiningTableStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DiningTableCreateRequest {

    @NotNull
    private Long branchId;

    @NotBlank
    private String tableName;

    private DiningTableStatus status;
}
