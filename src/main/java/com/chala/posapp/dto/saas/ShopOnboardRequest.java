package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopOnboardRequest {

    @NotBlank
    @Size(min = 3, max = 80)
    private String tenantId;

    @NotBlank
    @Size(min = 3, max = 120)
    private String shopName;

    @NotBlank
    @Size(min = 3, max = 50)
    private String adminUsername;

    @NotBlank
    @Size(min = 6, max = 100)
    private String adminPassword;

    @NotNull
    @Positive
    private Long planId;

    @PositiveOrZero
    private Double amountPaid;

    @Size(max = 120)
    private String initialBranchName;

    @Size(max = 255)
    private String initialBranchAddress;

    @Size(max = 30)
    private String initialBranchPhone;

    @Size(max = 255)
    private String note;
}
