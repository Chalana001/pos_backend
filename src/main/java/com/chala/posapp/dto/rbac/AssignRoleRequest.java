package com.chala.posapp.dto.rbac;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignRoleRequest {
    @NotNull
    private Long roleId;

    @NotNull
    private Long expectedVersion;
}
