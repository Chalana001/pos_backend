package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyClaimStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WarrantyClaimUpdateRequest {

    @NotNull
    private WarrantyClaimStatus status;

    private String resolutionNote;
}
