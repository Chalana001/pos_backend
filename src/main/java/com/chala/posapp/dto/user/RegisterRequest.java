package com.chala.posapp.dto.user;

import com.chala.posapp.util.validation.PasswordComplexity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    // MISS-08: enforce password complexity — this seeds an ADMIN account, so it must
    // meet the same bar as every other password-setting endpoint.
    @NotBlank
    @PasswordComplexity
    private String password;
}

