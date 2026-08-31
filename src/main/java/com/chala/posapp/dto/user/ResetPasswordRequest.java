package com.chala.posapp.dto.user;

import com.chala.posapp.util.validation.PasswordComplexity;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResetPasswordRequest {

    // MISS-08: enforce password complexity
    @NotBlank
    @PasswordComplexity
    private String newPassword;
}
