package com.chala.posapp.service;

import com.chala.posapp.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Component;

/**
 * The single place that answers "which caches does this kind of write invalidate?".
 *
 * <p><b>Why this exists.</b> Eviction used to be annotations scattered across the write
 * methods themselves, and it had drifted badly:
 *
 * <ul>
 *   <li>Only {@code OrderService.createOrder} carried them. {@code cancelOrder} and
 *       {@code recordPayment} evicted nothing, so a cancelled sale stayed in every
 *       report until its TTL expired.</li>
 *   <li>{@code importOfflineSale} calls {@code createOrderInternal} directly, so a sale
 *       synced back from an offline till bypassed the annotation on {@code createOrder}
 *       entirely and invalidated nothing at all.</li>
 *   <li>Eight report caches — returns summary, top suppliers, cashier performance,
 *       credit aging, expenses, inventory valuation, promotion effectiveness and
 *       warranties — had no eviction anywhere and were served up to an hour stale.</li>
 * </ul>
 *
 * <p><b>How to use it.</b> Call the method that names what happened, not the caches you
 * think it touches. When a new report cache is added, add it to the events that dirty it
 * here once, instead of hunting through every write path.
 *
 * <p><b>Why a separate bean.</b> {@code @CacheEvict} is applied by a proxy, so a service
 * calling its own annotated method would silently do nothing. Calling across beans makes
 * the proxy fire — the same reason {@code DashboardService} splits its cached reads out.
 *
 * <p>Most entries use {@code allEntries} because a report cache key carries a date range
 * the writer does not know. Dashboard KPIs are the exception: they are keyed on the
 * branch alone, they are the most frequently read cache in the system, and the write
 * paths that fire most often do know their branch — so those evict precisely, including
 * key 0, which is the "All Branches" view and which nothing used to touch.
 */
@Component
@RequiredArgsConstructor
public class ReportCacheInvalidator {

    /**
     * A sale was created, cancelled, or had a payment recorded against it.
     *
     * <p>Also covers offline sales replayed on sync — call this from
     * {@code createOrderInternal}, not from the public entry points, or the offline path
     * is missed again.
     */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,    key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)"),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,    key = "T(com.chala.posapp.util.CacheKeyUtils).key(0)"),
        @CacheEvict(value = CacheConfig.CACHE_DAILY_SALES,       allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_MONTHLY_SALES,     allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_SUMMARY, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_SELLING,   allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_CUSTOMERS, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT,        allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT_SUMMARY,allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_TREND,   allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_CATEGORY,allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_LOW_STOCK,         allEntries = true),
        // A sale also moves these, and none of them used to be evicted by anything.
        @CacheEvict(value = CacheConfig.CACHE_RPT_CASHIER_PERF,  allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROMOTION_EFF, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_INVENTORY_VAL, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_CREDIT_AGING,  allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_WARRANTY,      allEntries = true)
    })
    public void salesChanged(Long branchId) {
        // eviction only
    }

    /** A customer return or a supplier return was processed. */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_RPT_RETURNS_SUMMARY, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_SUMMARY,   allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_SELLING,     allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT,          allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT_SUMMARY,  allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_TREND,     allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_SALES_CATEGORY,  allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_INVENTORY_VAL,   allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_CREDIT_AGING,    allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_SUPPLIERS,   allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_LOW_STOCK,           allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,      allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_DAILY_SALES,         allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_MONTHLY_SALES,       allEntries = true)
    })
    public void returnsChanged() {
        // eviction only
    }

    /** An expense was recorded against a branch. */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,     key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)"),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,     key = "T(com.chala.posapp.util.CacheKeyUtils).key(0)"),
        @CacheEvict(value = CacheConfig.CACHE_RPT_EXPENSES,       allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT,         allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_PROFIT_SUMMARY, allEntries = true)
    })
    public void expensesChanged(Long branchId) {
        // eviction only
    }

    /** A customer settled credit. */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_RPT_CREDIT_AGING,  allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_CUSTOMERS, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,    allEntries = true)
    })
    public void creditChanged() {
        // eviction only
    }

    /** Stock moved without a sale: adjustment, transfer, or processing. */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_RPT_INVENTORY_VAL, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_LOW_STOCK,         allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,    allEntries = true)
    })
    public void stockChanged() {
        // eviction only
    }

    /** Goods were received, or a purchase was recorded against a supplier. */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_RPT_TOP_SUPPLIERS, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_RPT_INVENTORY_VAL, allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_LOW_STOCK,         allEntries = true),
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,    allEntries = true)
    })
    public void procurementChanged() {
        // eviction only
    }

    /** A warranty claim was raised or updated. */
    @CacheEvict(value = CacheConfig.CACHE_RPT_WARRANTY, allEntries = true)
    public void warrantyChanged() {
        // eviction only
    }
}
