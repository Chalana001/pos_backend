package com.chala.posapp.dto.saas;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SuperAdminDashboardResponse {

    private long totalShops;
    private long activeShops;
    private long expiredShops;
    private long blockedShops;

    /** Cash recorded in billing_records this calendar month. */
    private double totalRevenueThisMonth;

    /** Run-rate: every active shop's plan renewal price normalised to a month. */
    private double estimatedMrr;

    /** Shops whose subscription lapses within the next 7 and 30 days. */
    private long expiringWithin7Days;
    private long expiringWithin30Days;

    /** Shops onboarded this calendar month. */
    private long newShopsThisMonth;

    /** Shops carrying at least one module override. */
    private long customisedShops;

    private List<PlanBreakdown> planBreakdown;
    private List<ExpiringShop> expiringSoon;

    @Data
    @Builder
    public static class PlanBreakdown {
        private Long planId;
        private String planName;
        private long shopCount;
        private double mrr;
    }

    @Data
    @Builder
    public static class ExpiringShop {
        private String tenantId;
        private String shopName;
        private String planName;
        private java.time.LocalDateTime validUntil;
        private long daysLeft;
    }
}
