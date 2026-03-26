package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetShopAdminPasswordRequest {
    @NotBlank
    @Size(min = 6, max = 100)
    private String newPassword;
}
