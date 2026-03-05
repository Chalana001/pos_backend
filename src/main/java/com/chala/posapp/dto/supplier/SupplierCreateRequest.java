package com.chala.posapp.dto.supplier;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SupplierCreateRequest {

    private String name;
    private String phone;
    private String email;
    private String address;
    private Boolean active;
    private List<String> contacts;
    private Bank bank;
    @Getter @Setter
    public static class Bank {
        private String bankName;
        private String accountNumber;
        private String accountName;
        private String branchName;
    }
}