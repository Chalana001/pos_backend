package com.chala.posapp.dto;

import com.chala.posapp.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    @NotNull
    private Role role; // MANAGER / CASHIER / ADMIN (optional allow)

    // assign branch (required for MANAGER/CASHIER)
    private Long branchId;
}
