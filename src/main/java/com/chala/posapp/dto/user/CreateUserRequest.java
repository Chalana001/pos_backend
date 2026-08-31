package com.chala.posapp.dto.user;

import com.chala.posapp.entity.Role;
import com.chala.posapp.util.validation.PasswordComplexity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    // MISS-08: enforce password complexity (min 8 chars, uppercase, digit, special char)
    @NotBlank
    @PasswordComplexity
    private String password;

    @NotNull
    private Role role;

    private Long branchId;
}
