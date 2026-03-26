package com.chala.posapp.dto.saas;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShopSummaryResponse {
    private String tenantId;
    private String shopName;
    private String adminUsername;
    private String planName;
    private boolean active;
    private boolean blocked;
    private int maxBranches;
    private int extraBranches;
    private int allowedBranches;
    private long currentBranchCount;
    private LocalDateTime validUntil;
    private LocalDateTime createdAt;
}
