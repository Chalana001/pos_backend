package com.chala.posapp.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerCreateRequest {

    @NotBlank
    @Size(min = 2, max = 120)
    private String name;

    @NotBlank
    @Size(min = 9, max = 20)
    private String phone;

    @Size(max = 255)
    private String address;

    @PositiveOrZero
    private Double creditLimit; // optional; null means no configured limit
}
