package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class CustomerBehaviorResponse {
    private long activeCustomersInPeriod;
    private long newCustomers;
    private long returningCustomers;
    private double repeatRatePercent;
    private long periodOrders;
    private double averageOrdersPerActiveCustomer;
    private List<CustomerBehavior> customers;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CustomerBehavior {
        private Long customerId;
        private String customerName;
        private String phone;
        private LocalDateTime firstPurchaseAt;
        private LocalDateTime lastPurchaseAt;
        private long periodOrderCount;
        private long lifetimeOrderCount;
        private double periodSpend;
        private double lifetimeSpend;
        private double averagePeriodOrder;
        private double currentDue;
        private long daysSinceLastPurchase;
        private String inactivityBucket;
        private boolean newCustomer;
    }
}
