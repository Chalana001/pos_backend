package com.chala.posapp.dto.rbac;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Data
public class CreateRoleRequest {
    @NotBlank
    @Size(max = 80)
    private String roleKey;

    @NotBlank
    @Size(max = 100)
    private String displayName;

    private Long templateRoleId;
    private Set<String> permissionKeys;
}
