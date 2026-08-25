package com.chala.posapp.service;

import com.chala.posapp.config.CacheConfig;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.service.TenantDatabaseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * MISS-05: Nightly cache warm-up.
 *
 * Runs at 00:05 every day (5 minutes past midnight) so that the first user
 * of the new day hits a warm cache instead of a cold DB.
 *
 * Strategy:
 *  1. Iterate over all active tenants (from TenantDatabaseRegistry).
 *  2. For each tenant, evict the previous day's cached entries so stale data
 *     doesn't survive into the new day.
 *  3. Pre-warm dashboard KPIs and "today" charts by calling the service methods
 *     — Spring Cache intercepts the call and populates the Caffeine cache.
 *
 * Failures in one tenant must not abort other tenants — each is wrapped in
 * try/catch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportWarmupScheduler {

    private final TenantDatabaseRegistry tenantDatabaseRegistry;
    private final BranchRepository branchRepository;
    private final DashboardService dashboardService;
    private final CacheManager cacheManager;

    /**
     * Evict yesterday's caches and pre-warm today's dashboard for every branch.
     * Cron: 00:05 daily — adjust via app property if needed.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Colombo")
    public void evictAndWarmDashboard() {
        log.info("[ReportWarmup] Starting nightly cache eviction + warm-up");

        List<String> tenants = tenantDatabaseRegistry.getActiveTenantIds();
        log.info("[ReportWarmup] Processing {} tenants", tenants.size());

        for (String tenantId : tenants) {
            try {
                TenantContext.setTenant(tenantId);
                evictAllCaches();
                warmDashboardForTenant();
            } catch (Exception e) {
                log.error("[ReportWarmup] Failed for tenant '{}': {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("[ReportWarmup] Nightly warm-up complete");
    }

    /**
     * Evict all report + dashboard cache entries so the new day starts fresh.
     */
    private void evictAllCaches() {
        String[] cacheNames = {
            CacheConfig.CACHE_DASHBOARD_KPIS,
            CacheConfig.CACHE_DAILY_SALES,
            CacheConfig.CACHE_MONTHLY_SALES,
            CacheConfig.CACHE_LOW_STOCK,
            CacheConfig.CACHE_RPT_TOP_SELLING,
            CacheConfig.CACHE_RPT_TOP_CUSTOMERS,
            CacheConfig.CACHE_RPT_TOP_SUPPLIERS,
            CacheConfig.CACHE_RPT_SALES_SUMMARY,
            CacheConfig.CACHE_RPT_PROFIT,
            CacheConfig.CACHE_RPT_SALES_TREND,
            CacheConfig.CACHE_RPT_SALES_CATEGORY,
            CacheConfig.CACHE_RPT_PROFIT_SUMMARY,
            CacheConfig.CACHE_RPT_RETURNS_SUMMARY
        };
        for (String name : cacheNames) {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
        log.debug("[ReportWarmup] Caches evicted for tenant '{}'", TenantContext.getTenant());
    }

    /**
     * Pre-warm the dashboard KPI cache for every active branch in this tenant.
     * We call dashboardService methods directly — Spring AOP intercepts and
     * populates the Caffeine cache so the first real user gets a cache hit.
     */
    private void warmDashboardForTenant() {
        // Branch 0 is the "All Branches" view. It is a real cache entry that owners hit
        // first thing in the morning, and warming only the individual branches left it
        // permanently cold.
        List<Long> branchIds = new ArrayList<>();
        branchIds.add(0L);
        branchRepository.findAll().stream()
                .filter(Branch::isActive)
                .map(Branch::getId)
                .forEach(branchIds::add);

        LocalDate today = LocalDate.now();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        for (Long branchId : branchIds) {
            try {
                // The *ForBranch variants take an already-resolved branch and do not read
                // the SecurityContext. The public todayKpis()/dailySales() entry points do,
                // via securityUtils.getCurrentUser() — and on a scheduled thread there is
                // no authentication, so every branch used to fail here and get logged as
                // "Skipped". The warm-up has never actually warmed anything.
                dashboardService.todayKpisForBranch(branchId);
                dashboardService.dailySalesForBranch(branchId, thirtyDaysAgo, today);
                dashboardService.monthlySalesForBranch(branchId, thirtyDaysAgo, today);

                log.debug("[ReportWarmup] Warmed branch {}", branchId);
            } catch (Exception e) {
                log.warn("[ReportWarmup] Skipped branch {}: {}", branchId, e.getMessage());
            }
        }
    }
}
