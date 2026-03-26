package com.chala.posapp.service;

import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.report.CreditDueResponse;
import com.chala.posapp.dto.report.ProfitReportResponse;
import com.chala.posapp.dto.report.ProfitSummaryResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.SalesSummaryResponse;
import com.chala.posapp.dto.report.SalesTrendPoint;
import com.chala.posapp.dto.report.TopCustomerResponse;
import com.chala.posapp.dto.report.TopSellingItemResponse;
import com.chala.posapp.dto.report.TopSupplierResponse;
import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.ReportRepository;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.util.DateRangeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
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

    public SalesSummaryResponse salesSummary(Long requestedBranchId, LocalDate from, LocalDate to) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        double total = reportRepository.totalSales(tenantId, branchId, range.from(), range.to());
        double cash = reportRepository.cashSales(tenantId, branchId, range.from(), range.to());
        double credit = reportRepository.creditSales(tenantId, branchId, range.from(), range.to());
        double discount = reportRepository.totalDiscount(tenantId, branchId, range.from(), range.to());
        long orders = reportRepository.totalOrders(tenantId, branchId, range.from(), range.to());

        return SalesSummaryResponse.builder()
                .totalSales(total)
                .cashSales(cash)
                .creditSales(credit)
                .totalDiscount(discount)
                .totalOrders(orders)
                .build();
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.topSellingRaw(tenantId, branchId, range.from(), range.to(), limit).stream()
                .map(r -> TopSellingItemResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .qtySold(((Number) r[2]).longValue())
                        .revenue(((Number) r[3]).doubleValue())
                        .build())
                .toList();
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, LocalDate from, LocalDate to, int limit) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.profitReportRaw(tenantId, branchId, range.from(), range.to(), limit).stream()
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

    public List<SalesTrendPoint> salesTrend(Long requestedBranchId, LocalDate from, LocalDate to, String type) {
        String tenantId = TenantContext.getTenant();
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        Long effectiveBranchId = (branchId == null) ? 0L : branchId;
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        if ("MONTHLY".equalsIgnoreCase(type)) {
            List<Object[]> rows = reportRepository.monthlySalesRaw(tenantId, effectiveBranchId, range.from(), range.to());

            return rows.stream().map(r -> {
                String monthStr = (String) r[0];
                double amount = ((Number) r[1]).doubleValue();
                LocalDate date = LocalDate.parse(monthStr + "-01");
                return new SalesTrendPoint(date, amount, 0);
            }).toList();
        }

        List<Object[]> rows = reportRepository.dailySalesRaw(tenantId, effectiveBranchId, range.from(), range.to());

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

    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, LocalDate from, LocalDate to) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        List<Object[]> rows = reportRepository.salesByCategoryRaw(tenantId, branchId, range.from(), range.to());

        return rows.stream().map(r -> new CategorySalesResponse(
                (String) r[0],
                ((Number) r[1]).doubleValue()
        )).toList();
    }

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
                        .contactNo((String) r[2])
                        .purchaseCount(((Number) r[3]).longValue())
                        .totalPurchased(((Number) r[4]).doubleValue())
                        .build())
                .toList();
    }

    public ProfitSummaryResponse getProfitSummary(Long requestedBranchId, LocalDate from, LocalDate to) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        List<Object[]> rawData = reportRepository.profitReportRaw(tenantId, branchId, range.from(), range.to(), 1000000);

        double totalRevenue = 0;
        double totalCost = 0;
        double grossProfit = 0;

        for (Object[] row : rawData) {
            totalRevenue += ((Number) row[3]).doubleValue();
            totalCost += ((Number) row[4]).doubleValue();
            grossProfit += ((Number) row[5]).doubleValue();
        }

        double totalExpenses = reportRepository.getTotalExpenses(tenantId, branchId, range.from(), range.to());
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
