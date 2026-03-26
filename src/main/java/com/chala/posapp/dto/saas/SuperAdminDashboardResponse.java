package com.chala.posapp.dto.saas;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SuperAdminDashboardResponse {
    private long totalShops;
    private long activeShops;
    private long expiredShops;
    private double totalRevenueThisMonth;
}
