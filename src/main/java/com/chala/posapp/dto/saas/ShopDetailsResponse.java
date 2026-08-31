package com.chala.posapp.dto.saas;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShopDetailsResponse {
    private String tenantId;
    private String shopName;
    private String adminUsername;
    private String planName;
    private String planBillingCycle;
    private boolean active;
    private boolean blocked;
    private int maxBranches;
    private int extraBranches;
    private int allowedBranches;
    private long currentBranchCount;
    private LocalDateTime validUntil;
    private LocalDateTime createdAt;
    private String notes;
    private String mainBranchName;
    private String mainBranchAddress;
    private String mainBranchPhone;

    private String businessType;
    private String contactPhone;
    private String contactEmail;

    /** Modules this shop can actually use right now, after plan and overrides. */
    private int enabledModuleCount;
    private int totalModuleCount;

    /** How many modules this shop has been given or denied outside its plan. */
    private int moduleOverrideCount;

    /** Everything ever billed to this shop, from billing_records. */
    private double lifetimeValue;
}
