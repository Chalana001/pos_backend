package com.chala.posapp.service;

import com.chala.posapp.dto.dashboard.DashboardKpiResponse;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.DashboardRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.chala.posapp.dto.chart.DailySalesResponse;
import com.chala.posapp.dto.chart.MonthlySalesResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.ADMIN) {
            if (requestedBranchId == null)
                throw new NotAssignedException("branchId required for admin");
            return requestedBranchId;
        }

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new NotAssignedException("Manager branch not assigned");
            return user.getBranchId();
        }

        throw new BadRequestException("Not allowed");
    }

    public DashboardKpiResponse todayKpis(Long requestedBranchId) {
        String tenantId = TenantContext.getTenant();

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        LocalDate today = LocalDate.now();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59);

        return DashboardKpiResponse.builder()
                .todaySales(dashboardRepository.todaySales(tenantId, branchId, from, to))
                .cashSales(dashboardRepository.cashSales(tenantId, branchId, from, to))
                .creditSales(dashboardRepository.creditSales(tenantId, branchId, from, to))
                .todayDiscount(dashboardRepository.todayDiscount(tenantId, branchId, from, to))
                .todayOrders(dashboardRepository.todayOrders(tenantId, branchId, from, to))
                .todayExpenses(dashboardRepository.todayExpenses(tenantId, branchId, from, to))
                .todayCashDrops(dashboardRepository.todayCashDrops(tenantId, branchId, from, to))
                .lowStockCount(dashboardRepository.lowStockCount(tenantId, branchId))
                .totalDue(dashboardRepository.totalDue(tenantId))
                .build();
    }

    public List<DailySalesResponse> dailySales(Long requestedBranchId,
                                               LocalDate from,
                                               LocalDate to) {
        String tenantId = TenantContext.getTenant();

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(23, 59, 59);

        return dashboardRepository.dailySalesRaw(tenantId, branchId, fromDt, toDt).stream()
                .map(r -> DailySalesResponse.builder()
                        .date(r[0].toString()) // yyyy-MM-dd
                        .sales(((Number) r[1]).doubleValue())
                        .build())
                .toList();
    }

    public List<MonthlySalesResponse> monthlySales(Long requestedBranchId,
                                                   LocalDate from,
                                                   LocalDate to) {
        String tenantId = TenantContext.getTenant();

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(23, 59, 59);

        // tenantId එක පාස් කරනවා
        return dashboardRepository.monthlySalesRaw(tenantId, branchId, fromDt, toDt).stream()
                .map(r -> MonthlySalesResponse.builder()
                        .month(r[0].toString()) // yyyy-MM
                        .sales(((Number) r[1]).doubleValue())
                        .build())
                .toList();
    }
}