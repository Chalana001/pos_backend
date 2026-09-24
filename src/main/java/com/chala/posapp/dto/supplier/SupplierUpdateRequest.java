package com.chala.posapp.dto.supplier;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Edit an existing supplier.
 *
 * Every field is optional: a null means "leave this alone", which matches how
 * CustomerUpdateRequest behaves. The screens send the whole form, but making the
 * service partial-safe means a future caller that only wants to attach a phone
 * number does not have to read and echo back everything else.
 *
 * There is deliberately no `contacts` field. The entity has a SupplierContact list
 * but no screen edits it, and accepting a list here would invite a partial update
 * to silently delete rows it never meant to touch.
 */
@Getter
@Setter
public class SupplierUpdateRequest {

    @NotBlank(message = "Supplier name is required")
    @Size(max = 255, message = "Supplier name must be 255 characters or fewer")
    private String name;

    /**
     * Optional, and blank clears it. Sending "" or "   " stores NULL rather than an
     * empty string, because the unique index would otherwise let only one supplier
     * hold a blank number.
     */
    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phone;

    @Email(message = "Enter a valid email address")
    @Size(max = 100, message = "Email must be 100 characters or fewer")
    private String email;

    @Size(max = 1000, message = "Address must be 1000 characters or fewer")
    private String address;

    private Boolean active;

    private Bank bank;

    @Getter @Setter
    public static class Bank {
        private String bankName;
        private String accountNumber;
        private String accountName;
        private String branchName;
    }
}
