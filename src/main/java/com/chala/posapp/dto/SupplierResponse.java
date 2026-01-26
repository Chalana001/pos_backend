package com.chala.posapp.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SupplierResponse {
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String address;
    private Boolean active;

    // Nested Details
    private BankDetailsDto bankDetails;
    private List<ContactDto> contacts;

    @Data
    @Builder
    public static class BankDetailsDto {
        private String bankName;
        private String branchName;
        private String accountNumber;
        private String accountName;
    }

    @Data
    @Builder
    public static class ContactDto {
        private String contactName; // හෝ phone number
        // ඔයාගේ SupplierContact entity එකේ තියෙන field එක අනුව මේක වෙනස් වෙන්න පුළුවන්
    }
}