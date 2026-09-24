package com.chala.posapp.dto.supplier;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SupplierCreateRequest {

    @NotBlank(message = "Supplier name is required")
    @Size(max = 255, message = "Supplier name must be 255 characters or fewer")
    private String name;
    /**
     * Optional. Blank counts as "not given" and is stored as NULL -- see
     * SupplierService.normalizeOptional and the note on Supplier.phone. Guarding the
     * length here turns an over-long number into a 400 with a readable message
     * rather than a truncation at the column.
     */
    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phone;
    @Email(message = "Enter a valid email address")
    @Size(max = 100, message = "Email must be 100 characters or fewer")
    private String email;
    @Size(max = 1000, message = "Address must be 1000 characters or fewer")
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