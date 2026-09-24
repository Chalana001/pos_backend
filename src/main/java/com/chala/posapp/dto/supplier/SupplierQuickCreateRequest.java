package com.chala.posapp.dto.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuickCreateRequest {
    @NotBlank(message = "Supplier name is required")
    @Size(max = 255, message = "Supplier name must be 255 characters or fewer")
    private String name;
    /** Optional, as the quick-add modal has always labelled it. */
    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phone;
    @Size(max = 1000, message = "Address must be 1000 characters or fewer")
    private String address;
}
