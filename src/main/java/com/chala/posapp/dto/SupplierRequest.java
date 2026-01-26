package com.chala.posapp.dto;

import lombok.Data;

@Data
public class SupplierRequest {
    private String name;
    private String phone;
    private String address;
    private String email;
}