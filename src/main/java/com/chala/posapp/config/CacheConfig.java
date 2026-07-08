package com.chala.posapp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * MISS-01: Caffeine in-memory cache configuration.
 *
 * Cache names and TTL policy:
 *  ┌────────────────────────────────┬──────────┬──────────────────────────────────────────┐
 *  │ Cache name                     │ TTL      │ Evicted by                               │
 *  ├────────────────────────────────┼──────────┼──────────────────────────────────────────┤
 *  │ dashboard-kpis                 │  5 min   │ any new order / expense / shift close    │
 *  │ daily-sales                    │  5 min   │ new order                                │
 *  │ monthly-sales                  │  5 min   │ new order                                │
 *  │ low-stock                      │  5 min   │ GRN / stock adjustment                   │
 *  │ report-top-selling             │  1 hr    │ new order                                │
 *  │ report-top-customers           │  1 hr    │ new order / customer payment             │
 *  │ report-top-suppliers           │  1 hr    │ new GRN                                  │
 *  │ report-sales-summary           │  1 hr    │ new order                                │
 *  │ report-profit                  │  1 hr    │ new order                                │
 *  │ report-sales-trend             │  1 hr    │ new order                                │
 *  │ report-sales-category          │  1 hr    │ new order                                │
 *  │ report-profit-summary          │  1 hr    │ new order / expense                      │
 *  │ report-returns-summary         │  1 hr    │ new return                               │
 *  └────────────────────────────────┴──────────┴──────────────────────────────────────────┘
 *
 * All caches are per-tenant because the cache key includes the tenant ID (via
 * {@code TenantContext.getTenant()}) — see {@code CacheKeyUtils}.
 *
 * Maximum 500 entries per cache; least-recently-used entries are evicted after
 * that to bound heap usage.
 */
@Configuration
public class CacheConfig {

    // ── TTLs ────────────────────────────────────────────────────────────────────

    /** 5-minute cache for live dashboard & low-stock data. */
    private static final long DASHBOARD_TTL_MINUTES = 5;

    /** 1-hour cache for heavier historical report queries. */
    private static final long REPORT_TTL_MINUTES = 60;

    /** Maximum cache entries before LRU eviction kicks in. */
    private static final long MAX_ENTRIES = 500;

    // ── Cache name constants (import these in service classes) ───────────────────

    public static final String CACHE_DASHBOARD_KPIS      = "dashboard-kpis";
    public static final String CACHE_DAILY_SALES          = "daily-sales";
    public static final String CACHE_MONTHLY_SALES        = "monthly-sales";
    public static final String CACHE_LOW_STOCK            = "low-stock";

    public static final String CACHE_RPT_TOP_SELLING      = "report-top-selling";
    public static final String CACHE_RPT_TOP_CUSTOMERS    = "report-top-customers";
    public static final String CACHE_RPT_TOP_SUPPLIERS    = "report-top-suppliers";
    public static final String CACHE_RPT_SALES_SUMMARY    = "report-sales-summary";
    public static final String CACHE_RPT_PROFIT           = "report-profit";
    public static final String CACHE_RPT_SALES_TREND      = "report-sales-trend";
    public static final String CACHE_RPT_SALES_CATEGORY   = "report-sales-category";
    public static final String CACHE_RPT_PROFIT_SUMMARY   = "report-profit-summary";
    public static final String CACHE_RPT_RETURNS_SUMMARY  = "report-returns-summary";

    // Part 5 — new report caches (1-hr TTL, same as other report caches)
    public static final String CACHE_RPT_CASHIER_PERF     = "report-cashier-perf";
    public static final String CACHE_RPT_INVENTORY_VAL    = "report-inventory-val";
    public static final String CACHE_RPT_EXPENSES         = "report-expenses";
    public static final String CACHE_RPT_CREDIT_AGING     = "report-credit-aging";
    public static final String CACHE_RPT_PROMOTION_EFF    = "report-promotion-eff";
    public static final String CACHE_RPT_WARRANTY         = "report-warranty";

    // ── Bean ────────────────────────────────────────────────────────────────────

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        /*
         * Dynamic cache creation is ON (default) — any cache name not pre-declared
         * below still gets created with the default spec. We set a sensible default
         * that matches the dashboard TTL so nothing stays stale forever.
         */
        manager.setCaffeine(defaultSpec());

        // Fine-grained specs per cache name
        manager.registerCustomCache(
                CACHE_DASHBOARD_KPIS,
                buildCache(DASHBOARD_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_DAILY_SALES,
                buildCache(DASHBOARD_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_MONTHLY_SALES,
                buildCache(DASHBOARD_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_LOW_STOCK,
                buildCache(DASHBOARD_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_TOP_SELLING,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_TOP_CUSTOMERS,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_TOP_SUPPLIERS,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_SALES_SUMMARY,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_PROFIT,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_SALES_TREND,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_SALES_CATEGORY,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_PROFIT_SUMMARY,
                buildCache(REPORT_TTL_MINUTES));

        manager.registerCustomCache(
                CACHE_RPT_RETURNS_SUMMARY,
                buildCache(REPORT_TTL_MINUTES));

        // Part 5 — new report caches
        manager.registerCustomCache(CACHE_RPT_CASHIER_PERF,  buildCache(REPORT_TTL_MINUTES));
        manager.registerCustomCache(CACHE_RPT_INVENTORY_VAL, buildCache(REPORT_TTL_MINUTES));
        manager.registerCustomCache(CACHE_RPT_EXPENSES,      buildCache(REPORT_TTL_MINUTES));
        manager.registerCustomCache(CACHE_RPT_CREDIT_AGING,  buildCache(REPORT_TTL_MINUTES));
        manager.registerCustomCache(CACHE_RPT_PROMOTION_EFF, buildCache(REPORT_TTL_MINUTES));
        manager.registerCustomCache(CACHE_RPT_WARRANTY,      buildCache(REPORT_TTL_MINUTES));

        return manager;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Caffeine<Object, Object> defaultSpec() {
        return Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(DASHBOARD_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private com.github.benmanes.caffeine.cache.Cache<Object, Object> buildCache(long ttlMinutes) {
        return Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .recordStats()          // exposes hit/miss metrics via Actuator
                .build();
    }
}
