package com.chala.posapp.dto.rbac;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReauthenticationRequest {
    @NotBlank
    private String password;
}
