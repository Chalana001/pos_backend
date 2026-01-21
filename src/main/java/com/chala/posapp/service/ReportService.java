package com.chala.posapp.service;

import com.chala.posapp.dto.report.*;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.ReportRepository;
import com.chala.posapp.repository.StockRepository;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final StockRepository stockRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
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

    public SalesSummaryResponse salesSummary(Long requestedBranchId, Instant from, Instant to) {
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        ZoneId zone = ZoneId.systemDefault(); // or ZoneId.of("Asia/Colombo")

        LocalDateTime fromLdt = LocalDateTime.ofInstant(from, zone);
        LocalDateTime toLdt = LocalDateTime.ofInstant(to, zone);


        double total = reportRepository.totalSales(branchId, fromLdt, toLdt);
        double cash = reportRepository.cashSales(branchId, fromLdt, toLdt);
        double credit = reportRepository.creditSales(branchId, fromLdt, toLdt);
        double discount = reportRepository.totalDiscount(branchId, fromLdt, toLdt);
        long orders = reportRepository.totalOrders(branchId, fromLdt, toLdt);

        return SalesSummaryResponse.builder()
                .totalSales(total)
                .cashSales(cash)
                .creditSales(credit)
                .totalDiscount(discount)
                .totalOrders(orders)
                .build();
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, Instant fromLdt, Instant toLdt, int limit) {
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        ZoneId zone = ZoneId.systemDefault(); // or ZoneId.of("Asia/Colombo")

        LocalDateTime from = LocalDateTime.ofInstant(fromLdt, zone);
        LocalDateTime to = LocalDateTime.ofInstant(toLdt, zone);

        int safeLimit = Math.max(1, Math.min(limit, 100));

        return reportRepository.topSellingRaw(branchId, from, to, safeLimit).stream()
                .map(r -> TopSellingItemResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .qtySold(((Number) r[2]).longValue())
                        .revenue(((Number) r[3]).doubleValue())
                        .build())
                .toList();
    }

    public List<LowStockResponse> lowStock(Long requestedBranchId) {
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        return stockRepository.lowStockRaw(branchId).stream()
                .map(r -> LowStockResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .quantity(((Number) r[2]).intValue())
                        .reorderLevel(((Number) r[3]).intValue())
                        .build())
                .toList();
    }

    public List<CreditDueResponse> creditDueList() {
        User user = getLoggedUser();
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)
            throw new RuntimeException("Not allowed");

        return customerRepository.creditDueRaw().stream()
                .map(r -> CreditDueResponse.builder()
                        .customerId(((Number) r[0]).longValue())
                        .customerName((String) r[1])
                        .dueAmount(((Number) r[2]).doubleValue())
                        .build())
                .toList();
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, Instant fromLdt, Instant toLdt, int limit) {

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        ZoneId zone = ZoneId.systemDefault(); // or ZoneId.of("Asia/Colombo")

        LocalDateTime from = LocalDateTime.ofInstant(fromLdt, zone);
        LocalDateTime to = LocalDateTime.ofInstant(toLdt, zone);

        int safeLimit = Math.max(1, Math.min(limit, 200));

        return reportRepository.profitReportRaw(branchId, from, to, safeLimit).stream()
                .map(r -> ProfitReportResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .qtySold(((Number) r[2]).longValue())
                        .revenue(((Number) r[3]).doubleValue())
                        .cost(((Number) r[4]).doubleValue())
                        .profit(((Number) r[5]).doubleValue())
                        .build())
                .toList();
    }

}
