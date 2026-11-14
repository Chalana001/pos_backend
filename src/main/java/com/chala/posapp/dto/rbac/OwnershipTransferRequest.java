package com.chala.posapp.dto.rbac;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OwnershipTransferRequest {
    @NotNull
    private Long targetUserId;

    @NotNull
    private Long expectedVersion;

    @jakarta.validation.constraints.NotBlank
    private String reauthenticationChallenge;
}
