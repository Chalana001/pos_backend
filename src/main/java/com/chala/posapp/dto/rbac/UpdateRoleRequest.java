package com.chala.posapp.dto.rbac;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Data
public class UpdateRoleRequest {
    @NotBlank
    @Size(max = 100)
    private String displayName;

    @NotNull
    private Set<String> permissionKeys;

    @NotNull
    private Long expectedVersion;
}
