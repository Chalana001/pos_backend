package com.chala.posapp.service;

import com.chala.posapp.config.CacheConfig;
import com.chala.posapp.dto.chart.DailySalesResponse;
import com.chala.posapp.dto.chart.MonthlySalesResponse;
import com.chala.posapp.dto.dashboard.DashboardKpiResponse;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.repository.DashboardRepository;
import com.chala.posapp.repository.ReportRepository;
import com.chala.posapp.util.CacheKeyUtils;
import com.chala.posapp.util.DateRangeUtils;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    // DUP-03/04 FIX: dailySalesRaw/monthlySalesRaw now called from ReportRepository (single source of truth)
    private final ReportRepository reportRepository;
    private final SecurityUtils securityUtils;
    /**
     * Self-reference so the resolve step can call the cached step THROUGH the proxy.
     * A plain this.todayKpisForBranch(...) would bypass @Cacheable entirely.
     * Same pattern as ReportExportJobService's emailService provider.
     */
    private final ObjectProvider<DashboardService> self;

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.ADMIN) {
            if (requestedBranchId == null) {
                throw new NotAssignedException("branchId required for admin");
            }
            return requestedBranchId;
        }

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null) {
                throw new NotAssignedException("Manager branch not assigned");
            }
            return user.getBranchId();
        }

        throw new BadRequestException("Not allowed");
    }

    /**
     * Resolve first, then cache. The cache key must be the branch we actually queried,
     * not the branch the caller asked for — a manager's request for someone else's
     * branch is clamped to their own, and keying on the raw request stored that
     * clamped payload under the branch they asked for.
     */
    public DashboardKpiResponse todayKpis(Long requestedBranchId) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        return self.getObject().todayKpisForBranch(branchId);
    }

    /**
     * Cached on an already-resolved branch. Also callable without a SecurityContext,
     * which is what lets ReportWarmupScheduler pre-warm it from a scheduled thread.
     */
    // MISS-01: Cache dashboard KPIs per branch for 5 minutes
    @Cacheable(value = CacheConfig.CACHE_DASHBOARD_KPIS,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)")
    public DashboardKpiResponse todayKpisForBranch(Long branchId) {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59);

        // PERF FIX: single DB round-trip instead of 9 separate queries
        // NOTE: native queries always return List<Object[]>; we unpack the first (and only) row.
        List<Object[]> rows = dashboardRepository.todayKpisAllInOne(branchId, from, to);
        Object[] row = (rows != null && !rows.isEmpty()) ? rows.get(0) : new Object[9];

        return DashboardKpiResponse.builder()
                .todaySales(toDouble(row[0]))
                .cashSales(toDouble(row[1]))
                .creditSales(toDouble(row[2]))
                .todayDiscount(toDouble(row[3]))
                .todayOrders(toLong(row[4]))
                .todayExpenses(toDouble(row[5]))
                .todayCashDrops(toDouble(row[6]))
                .lowStockCount(toLong(row[7]))
                .totalDue(toDouble(row[8]))
                .build();
    }

    // Eviction lives on the write paths themselves — see OrderService and ExpenseService.
    // A method here was never called by anything, and its keys did not match the
    // @Cacheable keys above, so it would have been a no-op even if it had been.

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : 0.0;
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : 0L;
    }

    public List<DailySalesResponse> dailySales(Long requestedBranchId, LocalDate from, LocalDate to) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        return self.getObject().dailySalesForBranch(branchId, from, to);
    }

    // MISS-01: Cache daily sales chart per branch+range for 5 min
    @Cacheable(value = CacheConfig.CACHE_DAILY_SALES,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId, #from, #to)")
    public List<DailySalesResponse> dailySalesForBranch(Long branchId, LocalDate from, LocalDate to) {
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.dailySalesRaw(branchId, range.from(), range.to()).stream()
                .map(r -> DailySalesResponse.builder()
                        .date(r[0].toString())
                        .sales(toDouble(r[1]))
                        .build())
                .toList();
    }

    public List<MonthlySalesResponse> monthlySales(Long requestedBranchId, LocalDate from, LocalDate to) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        return self.getObject().monthlySalesForBranch(branchId, from, to);
    }

    // MISS-01: Cache monthly sales chart per branch+range for 5 min
    @Cacheable(value = CacheConfig.CACHE_MONTHLY_SALES,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId, #from, #to)")
    public List<MonthlySalesResponse> monthlySalesForBranch(Long branchId, LocalDate from, LocalDate to) {
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        // BUG-03 FIX: use monthlySalesRaw() SQL aggregation instead of loading all
        // daily rows into memory and grouping in Java (was wasting heap for large datasets)
        return reportRepository.monthlySalesRaw(branchId, range.from(), range.to()).stream()
                .map(r -> MonthlySalesResponse.builder()
                        .month(r[0].toString())
                        .sales(toDouble(r[1]))
                        .build())
                .toList();
    }
}
