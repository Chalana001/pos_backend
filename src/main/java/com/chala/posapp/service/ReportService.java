package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.report.CreditDueResponse;
import com.chala.posapp.dto.report.CustomerPerformanceResponse;
import com.chala.posapp.dto.report.ProfitReportResponse;
import com.chala.posapp.dto.report.ProfitSummaryResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.SalesReportResponse;
import com.chala.posapp.dto.report.SalesSummaryResponse;
import com.chala.posapp.dto.report.SalesTrendPoint;
import com.chala.posapp.dto.report.SupplierPerformanceResponse;
import com.chala.posapp.dto.report.TopCustomerResponse;
import com.chala.posapp.dto.report.TopSellingItemResponse;
import com.chala.posapp.dto.report.TopSupplierResponse;
import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.SaleMode;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private static final int EXPORT_LIMIT = 100000;

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
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN) {
            return requestedBranchId;
        }
        if (user.getRole() == Role.MANAGER || user.getRole() == Role.CASHIER) {
            return user.getBranchId();
        }
        throw new BadRequestException("Not allowed");
    }

    private Long toQueryBranchId(Long branchId) {
        return branchId == null ? 0L : branchId;
    }

    public SalesSummaryResponse salesSummary(Long requestedBranchId, LocalDate from, LocalDate to) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        Long queryBranchId = toQueryBranchId(branchId);
        double total = reportRepository.totalSales(tenantId, queryBranchId, range.from(), range.to());
        double cash = reportRepository.cashSales(tenantId, queryBranchId, range.from(), range.to());
        double credit = reportRepository.creditSales(tenantId, queryBranchId, range.from(), range.to());
        double discount = reportRepository.totalDiscount(tenantId, queryBranchId, range.from(), range.to());
        long orders = reportRepository.totalOrders(tenantId, queryBranchId, range.from(), range.to());

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

        return topSelling(requestedBranchId, from, to, limit, null, "REVENUE");
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType) {
        return topSelling(requestedBranchId, from, to, limit, itemType, "REVENUE");
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType, String rankBy) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);
        String normalizedRankBy = normalizeRankBy(rankBy);

        return reportRepository.topSellingRaw(tenantId, toQueryBranchId(branchId), normalizedItemType, normalizedRankBy, range.from(), range.to(), limit).stream()
                .map(r -> {
                    double revenue = ((Number) r[5]).doubleValue();
                    double profit = ((Number) r[7]).doubleValue();
                    return TopSellingItemResponse.builder()
                            .itemId(((Number) r[0]).longValue())
                            .itemName((String) r[1])
                            .itemType(parseItemType(r[2]))
                            .qtyUnit(r[3] != null ? r[3].toString() : null)
                            .qtySold(((Number) r[4]).doubleValue())
                            .revenue(revenue)
                            .cost(((Number) r[6]).doubleValue())
                            .profit(profit)
                            .marginPercent(revenue == 0 ? 0 : (profit / revenue) * 100)
                            .build();
                })
                .toList();
    }

    public PageResponse<TopSellingItemResponse> productPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String itemType,
            String sortBy,
            String sortDirection
    ) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);
        String normalizedSortBy = normalizeProductSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<TopSellingItemResponse> items = reportRepository.productPerformanceRaw(
                        tenantId,
                        queryBranchId,
                        normalizedItemType,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(this::mapProductPerformance)
                .toList();
        long total = reportRepository.countProductPerformance(tenantId, queryBranchId, normalizedItemType, range.from(), range.to());
        return pageResponse(items, normalizedPage, normalizedSize, total);
    }

    public PageResponse<CustomerPerformanceResponse> customerPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedSortBy = normalizeCustomerSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<CustomerPerformanceResponse> items = reportRepository.customerPerformanceRaw(
                        tenantId,
                        queryBranchId,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(r -> CustomerPerformanceResponse.builder()
                        .customerId(((Number) r[0]).longValue())
                        .customerName((String) r[1])
                        .phone((String) r[2])
                        .orderCount(((Number) r[3]).longValue())
                        .totalSpent(((Number) r[4]).doubleValue())
                        .totalPaid(((Number) r[5]).doubleValue())
                        .totalDue(((Number) r[6]).doubleValue())
                        .averageOrderValue(((Number) r[7]).doubleValue())
                        .lastOrderAt(toLocalDateTime(r[8]))
                        .build())
                .toList();
        long total = reportRepository.countCustomerPerformance(tenantId, queryBranchId, range.from(), range.to());
        return pageResponse(items, normalizedPage, normalizedSize, total);
    }

    public PageResponse<SupplierPerformanceResponse> supplierPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedSortBy = normalizeSupplierSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<SupplierPerformanceResponse> items = reportRepository.supplierPerformanceRaw(
                        tenantId,
                        queryBranchId,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(r -> SupplierPerformanceResponse.builder()
                        .supplierId(((Number) r[0]).longValue())
                        .supplierName((String) r[1])
                        .contactNo((String) r[2])
                        .purchaseCount(((Number) r[3]).longValue())
                        .totalPurchased(((Number) r[4]).doubleValue())
                        .totalPaid(((Number) r[5]).doubleValue())
                        .totalDue(((Number) r[6]).doubleValue())
                        .averagePurchaseValue(((Number) r[7]).doubleValue())
                        .lastPurchaseAt(toLocalDateTime(r[8]))
                        .build())
                .toList();
        long total = reportRepository.countSupplierPerformance(tenantId, queryBranchId, range.from(), range.to());
        return pageResponse(items, normalizedPage, normalizedSize, total);
    }

    public PageResponse<SalesReportResponse> salesReport(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            int page,
            int size,
            String orderType,
            String sortBy,
            String sortDirection
    ) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedOrderType = normalizeOrderType(orderType);
        String normalizedSortBy = normalizeSalesSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<SalesReportResponse> items = reportRepository.salesReportRaw(
                        tenantId,
                        queryBranchId,
                        normalizedOrderType,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(r -> SalesReportResponse.builder()
                        .orderId(((Number) r[0]).longValue())
                        .invoiceNo((String) r[1])
                        .branchId(((Number) r[2]).longValue())
                        .branchName((String) r[3])
                        .customerId(r[4] == null ? null : ((Number) r[4]).longValue())
                        .customerName((String) r[5])
                        .customerPhone((String) r[6])
                        .cashierUserId(((Number) r[7]).longValue())
                        .cashierName((String) r[8])
                        .orderType(parseOrderTypeValue(r[9]))
                        .paymentMethod((String) r[10])
                        .saleMode(parseSaleMode(r[11]))
                        .status(parseOrderStatus(r[12]))
                        .subTotal(((Number) r[13]).doubleValue())
                        .discount(((Number) r[14]).doubleValue())
                        .grandTotal(((Number) r[15]).doubleValue())
                        .paidAmount(((Number) r[16]).doubleValue())
                        .dueAmount(((Number) r[17]).doubleValue())
                        .createdAt(toLocalDateTime(r[18]))
                        .build())
                .toList();
        long total = reportRepository.countSalesReport(tenantId, queryBranchId, normalizedOrderType, range.from(), range.to());
        return pageResponse(items, normalizedPage, normalizedSize, total);
    }

    public byte[] exportPerformanceReport(
            String reportType,
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            String itemType,
            String orderType,
            String sortBy,
            String sortDirection
    ) {
        String normalizedType = reportType == null ? "" : reportType.trim().toUpperCase();

        return switch (normalizedType) {
            case "SALES" -> exportSalesReport(requestedBranchId, from, to, orderType, sortBy, sortDirection);
            case "PRODUCT" -> exportProductPerformance(requestedBranchId, from, to, itemType, sortBy, sortDirection);
            case "CUSTOMER" -> exportCustomerPerformance(requestedBranchId, from, to, sortBy, sortDirection);
            case "SUPPLIER" -> exportSupplierPerformance(requestedBranchId, from, to, sortBy, sortDirection);
            default -> throw new BadRequestException("Invalid export reportType: " + reportType);
        };
    }

    private byte[] exportSalesReport(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            String orderType,
            String sortBy,
            String sortDirection
    ) {
        PageResponse<SalesReportResponse> page = salesReport(
                requestedBranchId,
                from,
                to,
                0,
                EXPORT_LIMIT,
                orderType,
                normalizeSalesSortBy(sortBy),
                normalizeSortDirection(sortDirection)
        );
        return buildWorkbook("Sales Report",
                List.of("Date", "Invoice", "Branch", "Customer", "Phone", "Cashier", "Type", "Payment", "Subtotal", "Discount", "Grand Total", "Paid", "Due"),
                page.getItems().stream()
                        .map(item -> List.of(
                                safe(item.getCreatedAt()),
                                safe(item.getInvoiceNo()),
                                safe(item.getBranchName()),
                                safe(item.getCustomerName()),
                                safe(item.getCustomerPhone()),
                                safe(item.getCashierName()),
                                safe(item.getOrderType()),
                                safe(item.getPaymentMethod()),
                                item.getSubTotal(),
                                item.getDiscount(),
                                item.getGrandTotal(),
                                item.getPaidAmount(),
                                item.getDueAmount()
                        ))
                        .toList());
    }

    private byte[] exportProductPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            String itemType,
            String sortBy,
            String sortDirection
    ) {
        PageResponse<TopSellingItemResponse> page = productPerformance(
                requestedBranchId,
                from,
                to,
                0,
                EXPORT_LIMIT,
                itemType,
                normalizeProductSortBy(sortBy),
                normalizeSortDirection(sortDirection)
        );
        return buildWorkbook("Product Performance",
                List.of("Item ID", "Item", "Type", "Qty Sold", "Unit", "Revenue", "Cost", "Profit", "Margin %"),
                page.getItems().stream()
                        .map(item -> List.of(
                                safe(item.getItemId()),
                                safe(item.getItemName()),
                                safe(item.getItemType()),
                                item.getQtySold(),
                                safe(item.getQtyUnit()),
                                item.getRevenue(),
                                item.getCost(),
                                item.getProfit(),
                                item.getMarginPercent()
                        ))
                        .toList());
    }

    private byte[] exportCustomerPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            String sortBy,
            String sortDirection
    ) {
        PageResponse<CustomerPerformanceResponse> page = customerPerformance(
                requestedBranchId,
                from,
                to,
                0,
                EXPORT_LIMIT,
                normalizeCustomerSortBy(sortBy),
                normalizeSortDirection(sortDirection)
        );
        return buildWorkbook("Customer Performance",
                List.of("Customer ID", "Customer", "Phone", "Orders", "Total Spent", "Total Paid", "Total Due", "Avg Order", "Last Order"),
                page.getItems().stream()
                        .map(item -> List.of(
                                safe(item.getCustomerId()),
                                safe(item.getCustomerName()),
                                safe(item.getPhone()),
                                item.getOrderCount(),
                                item.getTotalSpent(),
                                item.getTotalPaid(),
                                item.getTotalDue(),
                                item.getAverageOrderValue(),
                                safe(item.getLastOrderAt())
                        ))
                        .toList());
    }

    private byte[] exportSupplierPerformance(
            Long requestedBranchId,
            LocalDate from,
            LocalDate to,
            String sortBy,
            String sortDirection
    ) {
        PageResponse<SupplierPerformanceResponse> page = supplierPerformance(
                requestedBranchId,
                from,
                to,
                0,
                EXPORT_LIMIT,
                normalizeSupplierSortBy(sortBy),
                normalizeSortDirection(sortDirection)
        );
        return buildWorkbook("Supplier Performance",
                List.of("Supplier ID", "Supplier", "Phone", "Purchases", "Total Purchased", "Total Paid", "Total Due", "Avg Purchase", "Last Purchase"),
                page.getItems().stream()
                        .map(item -> List.of(
                                safe(item.getSupplierId()),
                                safe(item.getSupplierName()),
                                safe(item.getContactNo()),
                                item.getPurchaseCount(),
                                item.getTotalPurchased(),
                                item.getTotalPaid(),
                                item.getTotalDue(),
                                item.getAveragePurchaseValue(),
                                safe(item.getLastPurchaseAt())
                        ))
                        .toList());
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, LocalDate from, LocalDate to, int limit) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return profitReport(requestedBranchId, from, to, limit, null);
    }

    public List<ProfitReportResponse> profitReport(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);

        return reportRepository.profitReportRaw(tenantId, toQueryBranchId(branchId), normalizedItemType, range.from(), range.to(), limit).stream()
                .map(r -> ProfitReportResponse.builder()
                        .itemId(((Number) r[0]).longValue())
                        .itemName((String) r[1])
                        .itemType(parseItemType(r[2]))
                        .qtySold(((Number) r[3]).doubleValue())
                        .revenue(((Number) r[4]).doubleValue())
                        .cost(((Number) r[5]).doubleValue())
                        .profit(((Number) r[6]).doubleValue())
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
            Map<YearMonth, Double> monthlyTotals = new LinkedHashMap<>();

            for (Object[] row : reportRepository.dailySalesRaw(tenantId, effectiveBranchId, range.from(), range.to())) {
                YearMonth month = YearMonth.parse(row[0].toString().substring(0, 7));
                double amount = ((Number) row[1]).doubleValue();
                monthlyTotals.merge(month, amount, Double::sum);
            }

            return monthlyTotals.entrySet().stream()
                    .map(entry -> new SalesTrendPoint(entry.getKey().atDay(1), entry.getValue(), 0))
                    .toList();
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
        return salesByCategory(requestedBranchId, from, to, null);
    }

    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, LocalDate from, LocalDate to, String itemType) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);

        List<Object[]> rows = reportRepository.salesByCategoryRaw(tenantId, toQueryBranchId(branchId), normalizedItemType, range.from(), range.to());

        return rows.stream().map(r -> new CategorySalesResponse(
                (String) r[0],
                ((Number) r[1]).doubleValue()
        )).toList();
    }

    public List<RecentOrderResponse> recentOrders(Long requestedBranchId) {
        String tenantId = TenantContext.getTenant();
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        List<Object[]> rows = reportRepository.recentOrdersRaw(tenantId, toQueryBranchId(branchId));

        return rows.stream().map(r -> new RecentOrderResponse(
                ((Number) r[0]).longValue(),
                (String) r[1],
                ((Number) r[2]).doubleValue(),
                (String) r[3],
                toLocalDateTime(r[4])
        )).toList();
    }

    public List<LowStockResponse> lowStock(Long requestedBranchId) {
        Long branchId = resolveBranchId(getLoggedUser(), requestedBranchId);
        return stockBatchRepository.findLowStockItems(branchId);
    }

    public List<CreditDueResponse> creditDueList() {
        String tenantId = TenantContext.getTenant();
        return customerRepository.creditDueRaw(tenantId).stream()
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

        return reportRepository.topCustomersRaw(tenantId, toQueryBranchId(branchId), limit).stream()
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

        Long queryBranchId = toQueryBranchId(branchId);
        List<Object[]> rawData = reportRepository.profitReportRaw(tenantId, queryBranchId, null, range.from(), range.to(), 1000000);

        double totalRevenue = 0;
        double totalCost = 0;
        double grossProfit = 0;

        for (Object[] row : rawData) {
            totalRevenue += ((Number) row[4]).doubleValue();
            totalCost += ((Number) row[5]).doubleValue();
            grossProfit += ((Number) row[6]).doubleValue();
        }

        double totalExpenses = reportRepository.getTotalExpenses(tenantId, queryBranchId, range.from(), range.to());
        double netProfit = grossProfit - totalExpenses;

        return ProfitSummaryResponse.builder()
                .totalRevenue(totalRevenue)
                .totalCost(totalCost)
                .grossProfit(grossProfit)
                .totalExpenses(totalExpenses)
                .netProfit(netProfit)
                .build();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    private TopSellingItemResponse mapProductPerformance(Object[] r) {
        double revenue = ((Number) r[5]).doubleValue();
        double profit = ((Number) r[7]).doubleValue();
        return TopSellingItemResponse.builder()
                .itemId(((Number) r[0]).longValue())
                .itemName((String) r[1])
                .itemType(parseItemType(r[2]))
                .qtyUnit(r[3] != null ? r[3].toString() : null)
                .qtySold(((Number) r[4]).doubleValue())
                .revenue(revenue)
                .cost(((Number) r[6]).doubleValue())
                .profit(profit)
                .marginPercent(revenue == 0 ? 0 : (profit / revenue) * 100)
                .build();
    }

    private byte[] buildWorkbook(String sheetName, List<String> headers, List<List<Object>> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<Object> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    Cell cell = row.createCell(columnIndex);
                    Object value = values.get(columnIndex);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else {
                        cell.setCellValue(value == null ? "" : value.toString());
                    }
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new BadRequestException("Failed to generate Excel report");
        }
    }

    private Object safe(Object value) {
        return value == null ? "" : value;
    }

    private <T> PageResponse<T> pageResponse(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return PageResponse.<T>builder()
                .items(items)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(totalPages == 0 || page >= totalPages - 1)
                .build();
    }

    private int normalizePage(int page) {
        return Math.max(0, page);
    }

    private int normalizePageSize(int size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, EXPORT_LIMIT);
    }

    private String normalizeItemType(String itemType) {
        if (itemType == null || itemType.isBlank() || "ALL".equalsIgnoreCase(itemType)) {
            return null;
        }
        try {
            return ItemType.valueOf(itemType.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid itemType: " + itemType);
        }
    }

    private String normalizeRankBy(String rankBy) {
        if (rankBy == null || rankBy.isBlank()) {
            return "REVENUE";
        }
        String normalized = rankBy.trim().toUpperCase();
        if (!List.of("REVENUE", "QUANTITY", "PROFIT").contains(normalized)) {
            throw new BadRequestException("Invalid rankBy: " + rankBy);
        }
        return normalized;
    }

    private String normalizeProductSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "REVENUE";
        }
        String normalized = sortBy.trim().toUpperCase();
        if (!List.of("REVENUE", "QUANTITY", "PROFIT").contains(normalized)) {
            throw new BadRequestException("Invalid product sortBy: " + sortBy);
        }
        return normalized;
    }

    private String normalizeCustomerSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "TOTAL_SPENT";
        }
        String normalized = sortBy.trim().toUpperCase();
        if (!List.of("TOTAL_SPENT", "ORDER_COUNT", "TOTAL_DUE", "AVG_ORDER", "LAST_ORDER").contains(normalized)) {
            throw new BadRequestException("Invalid customer sortBy: " + sortBy);
        }
        return normalized;
    }

    private String normalizeSupplierSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "TOTAL_PURCHASED";
        }
        String normalized = sortBy.trim().toUpperCase();
        if (!List.of("TOTAL_PURCHASED", "PURCHASE_COUNT", "TOTAL_DUE", "AVG_PURCHASE", "LAST_PURCHASE").contains(normalized)) {
            throw new BadRequestException("Invalid supplier sortBy: " + sortBy);
        }
        return normalized;
    }

    private String normalizeSalesSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "DATE";
        }
        String normalized = sortBy.trim().toUpperCase();
        if (!List.of("DATE", "TOTAL", "PAID", "DUE", "DISCOUNT").contains(normalized)) {
            throw new BadRequestException("Invalid sales sortBy: " + sortBy);
        }
        return normalized;
    }

    private String normalizeSortDirection(String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) {
            return "DESC";
        }
        String normalized = sortDirection.trim().toUpperCase();
        if (!List.of("ASC", "DESC").contains(normalized)) {
            throw new BadRequestException("Invalid sortDirection: " + sortDirection);
        }
        return normalized;
    }

    private String normalizeOrderType(String orderType) {
        if (orderType == null || orderType.isBlank() || "ALL".equalsIgnoreCase(orderType)) {
            return null;
        }
        try {
            return OrderType.valueOf(orderType.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid orderType: " + orderType);
        }
    }

    private ItemType parseItemType(Object value) {
        if (value == null) {
            return null;
        }
        return ItemType.valueOf(value.toString());
    }

    private OrderType parseOrderTypeValue(Object value) {
        if (value == null) {
            return null;
        }
        return OrderType.valueOf(value.toString());
    }

    private SaleMode parseSaleMode(Object value) {
        if (value == null) {
            return null;
        }
        return SaleMode.valueOf(value.toString());
    }

    private OrderStatus parseOrderStatus(Object value) {
        if (value == null) {
            return null;
        }
        return OrderStatus.valueOf(value.toString());
    }
}
