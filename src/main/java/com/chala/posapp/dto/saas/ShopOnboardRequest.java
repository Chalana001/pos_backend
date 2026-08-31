package com.chala.posapp.dto.saas;

import com.chala.posapp.util.validation.PasswordComplexity;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopOnboardRequest {

    @NotBlank
    @Size(min = 3, max = 80)
    private String tenantId;

    @NotBlank
    @Size(min = 3, max = 120)
    private String shopName;

    @NotBlank
    @Size(min = 3, max = 50)
    private String adminUsername;

    // MISS-08: enforce password complexity. This is the shop's very first ADMIN
    // credential and often never changed, so it was the weakest password in the system.
    @NotBlank
    @PasswordComplexity
    private String adminPassword;

    @NotNull
    @Positive
    private Long planId;

    @PositiveOrZero
    private Double amountPaid;

    @Size(max = 120)
    private String initialBranchName;

    @Size(max = 255)
    private String initialBranchAddress;

    @Size(max = 30)
    private String initialBranchPhone;

    @Size(max = 255)
    private String note;

    @Min(1)
    @Max(12)
    @JsonAlias({"subscriptionMonths", "subscriptionYears"})
    private Integer subscriptionCycles = 1;

    /**
     * RETAIL, RESTAURANT or HYBRID. Decides which module overrides are applied on top of the
     * plan template at onboarding — a retail shop does not want the table map, a restaurant
     * does. Defaults to RETAIL when the panel does not send one.
     */
    private String businessType;

    @Size(max = 40)
    private String contactPhone;

    @Size(max = 120)
    private String contactEmail;

    @Size(max = 40)
    private String discountCode;

    /** Start on a trial instead of a paid period. Length comes from the plan's trialDays. */
    private Boolean startTrial;

    /** Days the shop keeps working after expiry before being cut off. */
    @Min(0)
    @Max(60)
    private Integer graceDays;

    private Boolean generateInvoice;
}
