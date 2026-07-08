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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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

    // MISS-01: Cache dashboard KPIs per branch for 5 minutes
    @Cacheable(value = CacheConfig.CACHE_DASHBOARD_KPIS,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId)")
    public DashboardKpiResponse todayKpis(Long requestedBranchId) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

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

    /**
     * MISS-01: Evict dashboard + sales caches when a new order is placed
     * or an expense is saved (called from OrderService / ExpenseService).
     */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.CACHE_DASHBOARD_KPIS,  key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)"),
        @CacheEvict(value = CacheConfig.CACHE_DAILY_SALES,     key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)"),
        @CacheEvict(value = CacheConfig.CACHE_MONTHLY_SALES,   key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId)")
    })
    public void evictDashboardCaches(Long branchId) {
        // eviction only — no body needed
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : 0.0;
    }

    private long toLong(Object val) {
        return val instanceof Number ? ((Number) val).longValue() : 0L;
    }

    // MISS-01: Cache daily sales chart per branch+range for 5 min
    @Cacheable(value = CacheConfig.CACHE_DAILY_SALES,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public List<DailySalesResponse> dailySales(Long requestedBranchId, LocalDate from, LocalDate to) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.dailySalesRaw(branchId, range.from(), range.to()).stream()
                .map(r -> DailySalesResponse.builder()
                        .date(r[0].toString())
                        .sales(toDouble(r[1]))
                        .build())
                .toList();
    }

    // MISS-01: Cache monthly sales chart per branch+range for 5 min
    @Cacheable(value = CacheConfig.CACHE_MONTHLY_SALES,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public List<MonthlySalesResponse> monthlySales(Long requestedBranchId, LocalDate from, LocalDate to) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
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
