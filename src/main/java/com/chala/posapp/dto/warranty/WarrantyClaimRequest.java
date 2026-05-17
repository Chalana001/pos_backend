package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyClaimActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WarrantyClaimRequest {

    @NotNull
    private WarrantyClaimActionType actionType;

    @NotBlank
    private String issueDescription;
}
