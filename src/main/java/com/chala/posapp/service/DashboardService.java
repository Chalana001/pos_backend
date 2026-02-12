package com.chala.posapp.service;

import com.chala.posapp.dto.dashboard.DashboardKpiResponse;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.DashboardRepository;
import com.chala.posapp.repository.UserRepository;
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
                throw new RuntimeException("branchId required for admin");
            return requestedBranchId;
        }

        if (user.getRole() == Role.MANAGER) {
            if (user.getBranchId() == null)
                throw new RuntimeException("Manager branch not assigned");
            return user.getBranchId();
        }

        throw new RuntimeException("Not allowed");
    }

    public DashboardKpiResponse todayKpis(Long requestedBranchId) {

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);


        LocalDate today = LocalDate.now();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.atTime(23, 59, 59);

        return DashboardKpiResponse.builder()
                .todaySales(dashboardRepository.todaySales(branchId, from, to))
                .cashSales(dashboardRepository.cashSales(branchId, from, to))
                .creditSales(dashboardRepository.creditSales(branchId, from, to))
                .todayDiscount(dashboardRepository.todayDiscount(branchId, from, to))
                .todayOrders(dashboardRepository.todayOrders(branchId, from, to))
                .todayExpenses(dashboardRepository.todayExpenses(branchId, from, to))
                .todayCashDrops(dashboardRepository.todayCashDrops(branchId, from, to))
                .lowStockCount(dashboardRepository.lowStockCount(branchId))
                .totalDue(dashboardRepository.totalDue())
                .build();
    }
    public List<DailySalesResponse> dailySales(Long requestedBranchId,
                                               LocalDate from,
                                               LocalDate to) {

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(23, 59, 59);

        return dashboardRepository.dailySalesRaw(branchId, fromDt, toDt).stream()
                .map(r -> DailySalesResponse.builder()
                        .date(r[0].toString()) // yyyy-MM-dd
                        .sales(((Number) r[1]).doubleValue())
                        .build())
                .toList();
    }

    public List<MonthlySalesResponse> monthlySales(Long requestedBranchId,
                                                   LocalDate from,
                                                   LocalDate to) {

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(23, 59, 59);

        return dashboardRepository.monthlySalesRaw(branchId, fromDt, toDt).stream()
                .map(r -> MonthlySalesResponse.builder()
                        .month(r[0].toString()) // yyyy-MM
                        .sales(((Number) r[1]).doubleValue())
                        .build())
                .toList();
    }

}
