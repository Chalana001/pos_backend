package com.chala.posapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BankAccountRequest {

    @NotBlank
    private String name;

    private String accountNumber;

    private String bankName;

    private boolean active = true;
}
