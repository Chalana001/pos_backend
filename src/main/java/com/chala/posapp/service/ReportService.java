package com.chala.posapp.service;

import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.*;
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

    // --- Helper Methods ---
    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private Long resolveBranchId(User user, Long requestedBranchId) {
        if (user.getRole() == Role.ADMIN) {
            return requestedBranchId; // Null is allowed for Admin (All branches)
        }
        if (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER) {
            return user.getBranchId(); // Managers/Cashiers locked to their branch
        }
        throw new RuntimeException("Not allowed");
    }

    private LocalDateTime toLDT(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    // --- Report Logic ---

    public SalesSummaryResponse salesSummary(Long requestedBranchId, Instant from, Instant to) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        LocalDateTime f = toLDT(from);
        LocalDateTime t = toLDT(to);

        double total = reportRepository.totalSales(branchId, f, t);
        double cash = reportRepository.cashSales(branchId, f, t);
        double credit = reportRepository.creditSales(branchId, f, t);
        double discount = reportRepository.totalDiscount(branchId, f, t);
        long orders = reportRepository.totalOrders(branchId, f, t);

        // Gross Profit Calculation (Total Revenue - Total Cost)
        // Note: For better performance, you might create a specific query for total profit sum
        // But for summary, if needed, we can sum up profitReportRaw or add a new query.
        // For now, let's keep it simple.

        return SalesSummaryResponse.builder()
                .totalSales(total)
                .cashSales(cash)
                .creditSales(credit)
                .totalDiscount(discount)
                .totalOrders(orders)
                .build();
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, Instant from, Instant to, int limit) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return reportRepository.topSellingRaw(branchId, toLDT(from), toLDT(to), limit).stream()
                .map(r -> TopSellingItemResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .qtySold(((Number) r[2]).longValue())
                        .revenue(((Number) r[3]).doubleValue())
                        .build())
                .toList();
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, Instant from, Instant to, int limit) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return reportRepository.profitReportRaw(branchId, toLDT(from), toLDT(to), limit).stream()
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
        User user = getLoggedUser();
        Long branchId = resolveBranchId(user, requestedBranchId);

        // Repository එකට යවන්න 0 හෝ Branch ID එක සකසා ගැනීම
        // (ඔයාගේ Query එකේ ':branchId = 0' කියල තියෙන නිසා, null ආවොත් 0 යවන්න ඕන)
        Long effectiveBranchId = (branchId == null) ? 0L : branchId;

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime fromDt = LocalDateTime.ofInstant(from, zone);
        LocalDateTime toDt = LocalDateTime.ofInstant(to, zone);

        if ("MONTHLY".equalsIgnoreCase(type)) {
            // 🔥 MONTHLY: දැනට තියෙන monthlySalesRaw එක පාවිච්චි කිරීම
            // Return වෙන්නේ: [String("2026-02"), Double(Sales)]
            List<Object[]> rows = reportRepository.monthlySalesRaw(effectiveBranchId, fromDt, toDt);

            return rows.stream().map(r -> {
                String monthStr = (String) r[0]; // "2026-02" වගේ එනවා
                double amount = ((Number) r[1]).doubleValue();

                // Chart එකට පෙන්නන්න මේ String එක දිනයක් බවට හරවන්න ඕන ("2026-02" -> "2026-02-01")
                LocalDate date = LocalDate.parse(monthStr + "-01");

                // Order Count එක Query එකේ නැති නිසා 0 යවනවා
                return new SalesTrendPoint(date, amount, 0);
            }).toList();

        } else {
            // 🔥 DAILY: දැනට තියෙන dailySalesRaw එක පාවිච්චි කිරීම
            // Return වෙන්නේ: [Date("2026-02-01"), Double(Sales)]
            List<Object[]> rows = reportRepository.dailySalesRaw(effectiveBranchId, fromDt, toDt);

            return rows.stream().map(r -> {
                // SQL Date එක LocalDate එකට හරවනවා
                LocalDate date;
                if (r[0] instanceof java.sql.Date) {
                    date = ((java.sql.Date) r[0]).toLocalDate();
                } else {
                    date = LocalDate.parse(r[0].toString()); // String එකක් ආවොත්
                }

                double amount = ((Number) r[1]).doubleValue();

                return new SalesTrendPoint(date, amount, 0);
            }).toList();
        }
    }

    // 🔥 NEW: Category Sales (Pie Chart)
    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, Instant from, Instant to) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        List<Object[]> rows = reportRepository.salesByCategoryRaw(branchId, toLDT(from), toLDT(to));

        return rows.stream().map(r -> new CategorySalesResponse(
                (String) r[0], // Category Name
                ((Number) r[1]).doubleValue() // Total Sales
        )).toList();
    }

    // 🔥 NEW: Recent Orders (Table)
    public List<RecentOrderResponse> recentOrders(Long requestedBranchId) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        List<Object[]> rows = reportRepository.recentOrdersRaw(branchId);

        return rows.stream().map(r -> new RecentOrderResponse(
                ((Number) r[0]).longValue(), // ID
                (String) r[1], // Invoice No
                ((Number) r[2]).doubleValue(), // Total
                (String) r[3], // Type (CASH/CREDIT)
                ((Timestamp) r[4]).toLocalDateTime() // Date
        )).toList();
    }

    public List<LowStockResponse> lowStock(Long requestedBranchId) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return stockBatchRepository.findLowStockItems(branchId);
    }

    public List<CreditDueResponse> creditDueList() {
        // Only Admin/Manager check is inside resolveBranchId or Security Config, but explicit check is ok
        return customerRepository.creditDueRaw().stream()
                .map(r -> CreditDueResponse.builder()
                        .customerId(((Number) r[0]).longValue())
                        .customerName((String) r[1])
                        .dueAmount(((Number) r[2]).doubleValue())
                        .build())
                .toList();
    }

    // Top Customers
    public List<TopCustomerResponse> topCustomers(Long requestedBranchId, int limit) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return reportRepository.topCustomersRaw(branchId, limit).stream()
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
        // Note: branchId is currently unused in the query because the column is missing in DB
        return reportRepository.topSuppliersRaw(limit).stream()
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
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        LocalDateTime f = toLDT(from);
        LocalDateTime t = toLDT(to);

        // 1. මුලින්ම Item Sales වලින් එන Gross Profit එක ගණනය කරගමු
        // (අපි කලින් හදපු profitReportRaw එකම පාවිච්චි කරමු Loop එකක් දාලා එකතු කරන්න)
        List<Object[]> rawData = reportRepository.profitReportRaw(branchId, f, t, 1000000); // Limit එක වැඩි කළා ඔක්කොම ගන්න

        double totalRevenue = 0;
        double totalCost = 0;
        double grossProfit = 0;

        for (Object[] row : rawData) {
            // Query එකේ පිළිවෙල: row[3]=revenue, row[4]=cost, row[5]=profit
            totalRevenue += ((Number) row[3]).doubleValue();
            totalCost += ((Number) row[4]).doubleValue();
            grossProfit += ((Number) row[5]).doubleValue();
        }

        // 2. දැන් Expenses ටික වෙනම අරගන්න
        double totalExpenses = reportRepository.getTotalExpenses(branchId, f, t);

        // 3. 🔥 මෙන්න Calculation එක: Net Profit = Gross Profit - Expenses
        double netProfit = grossProfit - totalExpenses;

        // 4. Response එක යවන්න
        return ProfitSummaryResponse.builder()
                .totalRevenue(totalRevenue)
                .totalCost(totalCost)
                .grossProfit(grossProfit)
                .totalExpenses(totalExpenses)
                .netProfit(netProfit)
                .build();
    }
}