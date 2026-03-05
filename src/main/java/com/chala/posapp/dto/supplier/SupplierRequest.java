package com.chala.posapp.dto.supplier;

import lombok.Data;

@Data
public class SupplierRequest {
    private String name;
    private String phone;
    private String address;
    private String email;
}