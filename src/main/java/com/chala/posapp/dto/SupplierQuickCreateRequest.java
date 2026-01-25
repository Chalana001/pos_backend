package com.chala.posapp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuickCreateRequest {
    private String name;
    private String phone;
    private String address;
}
