package com.chala.posapp.dto.paymentmethod;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentMethodRequest {

    /** Stable code. Required on create; ignored on update (codes are immutable). */
    @Size(max = 40)
    private String code;

    @NotBlank(message = "Display name is required")
    @Size(max = 120)
    private String displayName;

    @NotBlank(message = "Category is required")
    private String category;

    @NotBlank(message = "Reference policy is required")
    private String referencePolicy;

    @Size(max = 60)
    private String referenceLabel;

    private boolean includeInExpectedCash;

    private boolean allowsCredit;

    @Size(max = 60)
    private String iconKey;

    private boolean active = true;

    private Integer sortOrder;
}
