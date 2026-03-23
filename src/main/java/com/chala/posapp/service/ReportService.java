package com.chala.posapp.service;

import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.*;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final StockBatchRepository stockBatchRepository;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.ADMIN) {
            return requestedBranchId;
        }
        if (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER) {
            return user.getBranchId();
        }
        throw new BadRequestException("Not allowed");
    }

    private LocalDateTime toLDT(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    public SalesSummaryResponse salesSummary(Long requestedBranchId, Instant from, Instant to) {
        // Current Tenant එක Context එකෙන් ගන්නවා
        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        LocalDateTime f = toLDT(from);
        LocalDateTime t = toLDT(to);

        // Repository එකට tenantId එකත් එක්කම පාස් කරනවා
        double total = reportRepository.totalSales(tenantId, branchId, f, t);
        double cash = reportRepository.cashSales(tenantId, branchId, f, t);
        double credit = reportRepository.creditSales(tenantId, branchId, f, t);
        double discount = reportRepository.totalDiscount(tenantId, branchId, f, t);
        long orders = reportRepository.totalOrders(tenantId, branchId, f, t);

        return SalesSummaryResponse.builder()
                .totalSales(total)
                .cashSales(cash)
                .creditSales(credit)
                .totalDiscount(discount)
                .totalOrders(orders)
                .build();
    }

    // 🟢 2. Top Selling එකට Tenant ID එක එකතු කිරීම
    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, Instant from, Instant to, int limit) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);

        // මෙතනත් Repository එකට tenantId එක යවනවා
        return reportRepository.topSellingRaw(tenantId, branchId, toLDT(from), toLDT(to), limit).stream()
                .map(r -> TopSellingItemResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .qtySold(((Number) r[2]).longValue())
                        .revenue(((Number) r[3]).doubleValue())
                        .build())
                .toList();
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, Instant from, Instant to, int limit) {

        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);

        return reportRepository.profitReportRaw(tenantId, branchId, toLDT(from), toLDT(to), limit).stream()
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

//    public List<SalesTrendPoint> salesTrend(Long requestedBranchId, Instant from, Instant to) {
//        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
//        List<Object[]> rows = reportRepository.salesTrendRaw(branchId, toLDT(from), toLDT(to));
//
//        return rows.stream().map(r -> new SalesTrendPoint(
//                ((java.sql.Date) r[0]).toLocalDate(),
//                ((Number) r[1]).doubleValue(),
//                ((Number) r[2]).longValue()
//        )).toList();
//    }

    public List<SalesTrendPoint> salesTrend(Long requestedBranchId, Instant from, Instant to, String type) {
        // 1. Current Tenant එක Context එකෙන් ගන්නවා
        String tenantId = TenantContext.getTenant();

        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        // Branch එක null නම් 0L (All Branches) විදිහට සලකනවා
        Long effectiveBranchId = (branchId == null) ? 0L : branchId;

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime fromDt = LocalDateTime.ofInstant(from, zone);
        LocalDateTime toDt = LocalDateTime.ofInstant(to, zone);

        if ("MONTHLY".equalsIgnoreCase(type)) {
            // 2. Repository එකට tenantId එකත් එක්ක දත්ත පාස් කරනවා
            List<Object[]> rows = reportRepository.monthlySalesRaw(tenantId, effectiveBranchId, fromDt, toDt);

            return rows.stream().map(r -> {
                String monthStr = (String) r[0];
                double amount = ((Number) r[1]).doubleValue();
                LocalDate date = LocalDate.parse(monthStr + "-01");
                return new SalesTrendPoint(date, amount, 0);
            }).toList();

        } else {
            // 3. මෙතනත් tenantId එක පාස් කරනවා
            List<Object[]> rows = reportRepository.dailySalesRaw(tenantId, effectiveBranchId, fromDt, toDt);

            return rows.stream().map(r -> {
                LocalDate date;
                if (r[0] instanceof java.sql.Date) {
                    date = ((java.sql.Date) r[0]).toLocalDate();
                } else {
                    date = LocalDate.parse(r[0].toString());
                }
                double amount = ((Number) r[1]).doubleValue();
                return new SalesTrendPoint(date, amount, 0);
            }).toList();
        }
    }

    // 🟢 1. Sales By Category
    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, Instant from, Instant to) {
        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);

        List<Object[]> rows = reportRepository.salesByCategoryRaw(tenantId, branchId, toLDT(from), toLDT(to));

        return rows.stream().map(r -> new CategorySalesResponse(
                (String) r[0],
                ((Number) r[1]).doubleValue()
        )).toList();
    }

    // 🟢 2. Recent Orders
    public List<RecentOrderResponse> recentOrders(Long requestedBranchId) {
        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);

        List<Object[]> rows = reportRepository.recentOrdersRaw(tenantId, branchId);

        return rows.stream().map(r -> new RecentOrderResponse(
                ((Number) r[0]).longValue(),
                (String) r[1],
                ((Number) r[2]).doubleValue(),
                (String) r[3],
                ((Timestamp) r[4]).toLocalDateTime()
        )).toList();
    }

    public List<LowStockResponse> lowStock(Long requestedBranchId) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return stockBatchRepository.findLowStockItems(branchId);
    }

    public List<CreditDueResponse> creditDueList() {

        return customerRepository.creditDueRaw().stream()
                .map(r -> CreditDueResponse.builder()
                        .customerId(((Number) r[0]).longValue())
                        .customerName((String) r[1])
                        .dueAmount(((Number) r[2]).doubleValue())
                        .build())
                .toList();
    }

    // 🟢 1. Top Customers (Multi-tenant safe)
    public List<TopCustomerResponse> topCustomers(Long requestedBranchId, int limit) {
        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);

        return reportRepository.topCustomersRaw(tenantId, branchId, limit).stream()
                .map(r -> TopCustomerResponse.builder()
                        .customerId(((Number) r[0]).longValue())
                        .customerName((String) r[1])
                        .phone((String) r[2])
                        .orderCount(((Number) r[3]).longValue())
                        .totalSpent(((Number) r[4]).doubleValue())
                        .build())
                .toList();
    }

    public List<TopSupplierResponse> topSuppliers(Long requestedBranchId, int limit) {
        String tenantId = TenantContext.getTenant();

        return reportRepository.topSuppliersRaw(tenantId, limit).stream()
                .map(r -> TopSupplierResponse.builder()
                        .supplierId(((Number) r[0]).longValue())
                        .supplierName((String) r[1])
                        .contactNo((String) r[2]) // Phone number
                        .purchaseCount(((Number) r[3]).longValue())
                        .totalPurchased(((Number) r[4]).doubleValue())
                        .build())
                .toList();
    }

    public ProfitSummaryResponse getProfitSummary(Long requestedBranchId, Instant from, Instant to) {

        String tenantId = TenantContext.getTenant();

        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        LocalDateTime f = toLDT(from);
        LocalDateTime t = toLDT(to);

        List<Object[]> rawData = reportRepository.profitReportRaw(tenantId, branchId, f, t, 1000000);

        double totalRevenue = 0;
        double totalCost = 0;
        double grossProfit = 0;

        for (Object[] row : rawData) {
            totalRevenue += ((Number) row[3]).doubleValue();
            totalCost += ((Number) row[4]).doubleValue();
            grossProfit += ((Number) row[5]).doubleValue();
        }

        double totalExpenses = reportRepository.getTotalExpenses(tenantId, branchId, f, t);

        double netProfit = grossProfit - totalExpenses;

        return ProfitSummaryResponse.builder()
                .totalRevenue(totalRevenue)
                .totalCost(totalCost)
                .grossProfit(grossProfit)
                .totalExpenses(totalExpenses)
                .netProfit(netProfit)
                .build();
    }
}