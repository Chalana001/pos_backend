package com.chala.posapp.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerUpdateRequest {

    @Size(min = 2, max = 120)
    private String name;

    @Size(min = 9, max = 20)
    private String phone;

    @Size(max = 255)
    private String address;

    private Double creditLimit;

    private Boolean active;
}
