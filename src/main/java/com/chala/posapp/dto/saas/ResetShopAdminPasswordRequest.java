package com.chala.posapp.dto.saas;

import com.chala.posapp.util.validation.PasswordComplexity;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResetShopAdminPasswordRequest {
    // MISS-08: enforce password complexity
    @NotBlank
    @PasswordComplexity
    private String newPassword;
}
