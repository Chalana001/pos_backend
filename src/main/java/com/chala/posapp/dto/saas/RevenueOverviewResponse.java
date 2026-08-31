package com.chala.posapp.dto.saas;

import java.util.List;

/**
 * The revenue picture for the panel's billing dashboard.
 *
 * <p>Two different numbers here mean different things and the UI must label them as such:
 * {@code thisMonth} is <em>cash actually recorded</em> in {@code billing_records} this calendar
 * month, while {@code estimatedMrr} is <em>run-rate</em> — the sum of every active shop's plan
 * renewal price normalised to a month. They will rarely match, because an annual payment lands
 * in one month but earns over twelve.
 */
public record RevenueOverviewResponse(
        double thisMonth,
        double lastMonth,
        Double growthPercent,
        double lifetimeTotal,
        double estimatedMrr,
        double estimatedArr,
        long payingShops,
        double averageRevenuePerShop,
        List<ActionBucket> byActionType,
        List<MonthPoint> monthlyTrend,
        List<PlanBucket> byPlan,
        List<ShopValue> topShops
) {
    /** Revenue split by what it was for: onboarding, renewal, plan change, extra branches. */
    public record ActionBucket(String actionType, double total, long count) {
    }

    public record MonthPoint(int year, int month, String label, double total, long count) {
    }

    /**
     * @param mrr    run-rate contribution of every active shop on this plan
     * @param booked cash recorded against shops on this plan, lifetime
     */
    public record PlanBucket(Long planId, String planName, long shopCount, double mrr, double booked) {
    }

    public record ShopValue(String tenantId, String shopName, double lifetimeValue, long paymentCount) {
    }
}
