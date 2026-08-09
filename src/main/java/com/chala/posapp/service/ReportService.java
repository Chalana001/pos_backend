package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.report.CreditDueResponse;
import com.chala.posapp.dto.report.CustomerPerformanceResponse;
import com.chala.posapp.dto.report.ProfitReportResponse;
import com.chala.posapp.dto.report.ProfitSummaryResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.ReturnReasonBreakdownResponse;
import com.chala.posapp.dto.report.ReturnTrendPoint;
import com.chala.posapp.dto.report.ReturnsSummaryResponse;
import com.chala.posapp.dto.report.SalesReportResponse;
import com.chala.posapp.dto.report.SalesSummaryResponse;
import com.chala.posapp.dto.report.SalesTrendPoint;
import com.chala.posapp.dto.report.SupplierPerformanceResponse;
import com.chala.posapp.dto.report.TopCustomerResponse;
import com.chala.posapp.dto.report.TopReturnedItemResponse;
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
import com.chala.posapp.repository.CustomerRepository;
import com.chala.posapp.repository.ReportRepository;
import com.chala.posapp.repository.ProfitSummaryProjection;
import com.chala.posapp.repository.StockBatchRepository;
import com.chala.posapp.config.CacheConfig;
import com.chala.posapp.util.CacheKeyUtils;
import com.chala.posapp.util.DateRangeUtils;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private static final int EXPORT_LIMIT = 100000;

    private final ReportRepository reportRepository;
    private final CustomerRepository customerRepository;
    private final StockBatchRepository stockBatchRepository;
    // BUG-07 FIX: securityUtils.getCurrentUser() removed — use SecurityUtils.getCurrentUser() instead
    private final SecurityUtils securityUtils;

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

    // MISS-04 FIX: Guard against unbounded date ranges that would force full-table scans.
    // Max 366 days for standard reports, 1095 days (3 years) for trend/summary views.
    private static final long MAX_REPORT_DAYS  = 366;
    private static final long MAX_TREND_DAYS   = 1095;

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) return;
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days < 0) throw new BadRequestException("'from' date must not be after 'to' date");
        if (days > MAX_REPORT_DAYS) {
            throw new BadRequestException(
                "Date range exceeds maximum of " + MAX_REPORT_DAYS + " days for this report. " +
                "Use the trend endpoint for multi-year views.");
        }
    }

    private void validateTrendDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) return;
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days < 0) throw new BadRequestException("'from' date must not be after 'to' date");
        if (days > MAX_TREND_DAYS) {
            throw new BadRequestException(
                "Date range exceeds maximum of " + MAX_TREND_DAYS + " days for trend reports.");
        }
    }

    // MISS-01: Cache sales summary per branch+date range for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_SALES_SUMMARY,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public SalesSummaryResponse salesSummary(Long requestedBranchId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        Long queryBranchId = toQueryBranchId(branchId);
        double total = reportRepository.totalSales(queryBranchId, range.from(), range.to());
        double cash = reportRepository.cashSales(queryBranchId, range.from(), range.to());
        double credit = reportRepository.creditSales(queryBranchId, range.from(), range.to());
        double discount = reportRepository.totalDiscount(queryBranchId, range.from(), range.to());
        long orders = reportRepository.totalOrders(queryBranchId, range.from(), range.to());

        return SalesSummaryResponse.builder()
                .totalSales(total)
                .cashSales(cash)
                .creditSales(credit)
                .totalDiscount(discount)
                .totalOrders(orders)
                .build();
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit) {
        return topSelling(requestedBranchId, from, to, limit, null, "REVENUE");
    }

    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType) {
        return topSelling(requestedBranchId, from, to, limit, itemType, "REVENUE");
    }

    // MISS-01: Cache top-selling per branch+date+params for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_TOP_SELLING,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to, #limit, #itemType, #rankBy)")
    public List<TopSellingItemResponse> topSelling(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType, String rankBy) {
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);
        // BUG-06 FIX: topSellingRaw removed (duplicate SQL). Delegate to the unified
        // productPerformanceRaw with offset=0 and DESC direction for top-N queries.
        String normalizedRankBy = normalizeRankBy(rankBy);

        return reportRepository.productPerformanceRaw(
                        toQueryBranchId(branchId), normalizedItemType,
                        normalizedRankBy, "DESC",
                        range.from(), range.to(), limit, 0)
                .stream()
                .map(r -> {
                    double revenue = toDouble(r[5]);
                    double profit  = toDouble(r[7]);
                    return TopSellingItemResponse.builder()
                            .itemId(toLong(r[0]))
                            .itemName((String) r[1])
                            .itemType(parseItemType(r[2]))
                            .qtyUnit(toStr(r[3]))
                            .qtySold(toDouble(r[4]))
                            .revenue(revenue)
                            .cost(toDouble(r[6]))
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
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);
        String normalizedSortBy = normalizeProductSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<TopSellingItemResponse> items = reportRepository.productPerformanceRaw(
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
        long total = reportRepository.countProductPerformance(queryBranchId, normalizedItemType, range.from(), range.to());
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
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedSortBy = normalizeCustomerSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<CustomerPerformanceResponse> items = reportRepository.customerPerformanceRaw(
                        queryBranchId,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(r -> CustomerPerformanceResponse.builder()
                        .customerId(toLong(r[0]))
                        .customerName((String) r[1])
                        .phone((String) r[2])
                        .orderCount(toLong(r[3]))
                        .totalSpent(toDouble(r[4]))
                        .totalPaid(toDouble(r[5]))
                        .totalDue(toDouble(r[6]))
                        .averageOrderValue(toDouble(r[7]))
                        .lastOrderAt(toLocalDateTime(r[8]))
                        .build())
                .toList();
        long total = reportRepository.countCustomerPerformance(queryBranchId, range.from(), range.to());
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
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedSortBy = normalizeSupplierSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<SupplierPerformanceResponse> items = reportRepository.supplierPerformanceRaw(
                        queryBranchId,
                        normalizedSortBy,
                        normalizedSortDirection,
                        range.from(),
                        range.to(),
                        normalizedSize,
                        normalizedPage * normalizedSize
                ).stream()
                .map(r -> SupplierPerformanceResponse.builder()
                        .supplierId(toLong(r[0]))
                        .supplierName((String) r[1])
                        .contactNo((String) r[2])
                        .purchaseCount(toLong(r[3]))
                        .totalPurchased(toDouble(r[4]))
                        .totalPaid(toDouble(r[5]))
                        .totalDue(toDouble(r[6]))
                        .averagePurchaseValue(toDouble(r[7]))
                        .lastPurchaseAt(toLocalDateTime(r[8]))
                        .build())
                .toList();
        long total = reportRepository.countSupplierPerformance(queryBranchId, range.from(), range.to());
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
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedOrderType = normalizeOrderType(orderType);
        String normalizedSortBy = normalizeSalesSortBy(sortBy);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizePageSize(size);
        Long queryBranchId = toQueryBranchId(branchId);

        List<SalesReportResponse> items = reportRepository.salesReportRaw(
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
                        .orderId(toLong(r[0]))
                        .invoiceNo((String) r[1])
                        .branchId(toLong(r[2]))
                        .branchName((String) r[3])
                        .customerId(r[4] == null ? null : toLong(r[4]))
                        .customerName((String) r[5])
                        .customerPhone((String) r[6])
                        .cashierUserId(toLong(r[7]))
                        .cashierName((String) r[8])
                        .orderType(parseOrderTypeValue(r[9]))
                        .paymentMethod((String) r[10])
                        .saleMode(parseSaleMode(r[11]))
                        .status(parseOrderStatus(r[12]))
                        .subTotal(toDouble(r[13]))
                        .discount(toDouble(r[14]))
                        .grandTotal(toDouble(r[15]))
                        .paidAmount(toDouble(r[16]))
                        .dueAmount(toDouble(r[17]))
                        .createdAt(toLocalDateTime(r[18]))
                        .build())
                .toList();
        long total = reportRepository.countSalesReport(queryBranchId, normalizedOrderType, range.from(), range.to());
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
        return profitReport(requestedBranchId, from, to, limit, null);
    }

    // MISS-01: Cache profit report results for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_PROFIT,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to, #limit, #itemType)")
    public List<ProfitReportResponse> profitReport(Long requestedBranchId, LocalDate from, LocalDate to, int limit, String itemType) {
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);

        return reportRepository.profitReportRaw(toQueryBranchId(branchId), normalizedItemType, range.from(), range.to(), limit).stream()
                .map(r -> ProfitReportResponse.builder()
                        .itemId(toLong(r[0]))
                        .itemName((String) r[1])
                        .itemType(parseItemType(r[2]))
                        .qtySold(toDouble(r[3]))
                        .revenue(toDouble(r[4]))
                        .cost(toDouble(r[5]))
                        .profit(toDouble(r[6]))
                        .build())
                .toList();
    }

    // MISS-01: Cache sales trend for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_SALES_TREND,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to, #type)")
    public List<SalesTrendPoint> salesTrend(Long requestedBranchId, LocalDate from, LocalDate to, String type) {
        validateTrendDateRange(from, to);
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        Long effectiveBranchId = (branchId == null) ? 0L : branchId;
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        if ("MONTHLY".equalsIgnoreCase(type)) {
            // BUG-04 FIX: use monthlySalesRaw() SQL aggregation instead of loading
            // all daily rows into memory and grouping in Java per YearMonth
            return reportRepository.monthlySalesRaw(effectiveBranchId, range.from(), range.to()).stream()
                    .map(r -> {
                        // monthlySalesRaw returns 'YYYY-MM' — parse to first day of month
                        LocalDate monthStart = YearMonth.parse(r[0].toString()).atDay(1);
                        return new SalesTrendPoint(monthStart, toDouble(r[1]), 0);
                    })
                    .toList();
        }

        List<Object[]> rows = reportRepository.dailySalesRaw(effectiveBranchId, range.from(), range.to());

        return rows.stream().map(r -> {
            LocalDate date;
            if (r[0] instanceof java.sql.Date) {
                date = ((java.sql.Date) r[0]).toLocalDate();
            } else {
                date = LocalDate.parse(r[0].toString());
            }
            return new SalesTrendPoint(date, toDouble(r[1]), 0);
        }).toList();
    }

    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, LocalDate from, LocalDate to) {
        return salesByCategory(requestedBranchId, from, to, null);
    }

    // MISS-01: Cache sales-by-category for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_SALES_CATEGORY,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to, #itemType)")
    public List<CategorySalesResponse> salesByCategory(Long requestedBranchId, LocalDate from, LocalDate to, String itemType) {
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        String normalizedItemType = normalizeItemType(itemType);

        List<Object[]> rows = reportRepository.salesByCategoryRaw(toQueryBranchId(branchId), normalizedItemType, range.from(), range.to());

        return rows.stream().map(r -> new CategorySalesResponse(
                (String) r[0],
                toDouble(r[1])
        )).toList();
    }

    public List<RecentOrderResponse> recentOrders(Long requestedBranchId) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        List<Object[]> rows = reportRepository.recentOrdersRaw(toQueryBranchId(branchId));

        return rows.stream().map(r -> new RecentOrderResponse(
                toLong(r[0]),
                (String) r[1],
                toDouble(r[2]),
                (String) r[3],
                toLocalDateTime(r[4])
        )).toList();
    }

    // MISS-01: Cache low stock list for 5 minutes
    @Cacheable(value = CacheConfig.CACHE_LOW_STOCK,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId)")
    public List<LowStockResponse> lowStock(Long requestedBranchId) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        return stockBatchRepository.findLowStockItems(branchId);
    }

    public List<CreditDueResponse> creditDueList() {
        return customerRepository.creditDueRaw().stream()
                .map(r -> CreditDueResponse.builder()
                        .customerId(toLong(r[0]))
                        .customerName((String) r[1])
                        .dueAmount(toDouble(r[2]))
                        .build())
                .toList();
    }

    // PERF-06/07 FIX: Added from/to date range params — previously scanned ALL historical data.
    // Defaults to last 30 days when no range specified (sensible default for a dashboard widget).
    // MISS-01: Cache top-customers for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_TOP_CUSTOMERS,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #limit, #from, #to)")
    public List<TopCustomerResponse> topCustomers(Long requestedBranchId, int limit, LocalDate from, LocalDate to) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        LocalDate resolvedFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate resolvedTo   = to   != null ? to   : LocalDate.now();
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(resolvedFrom, resolvedTo);

        return reportRepository.topCustomersRaw(toQueryBranchId(branchId), range.from(), range.to(), limit).stream()
                .map(r -> TopCustomerResponse.builder()
                        .customerId(toLong(r[0]))
                        .customerName((String) r[1])
                        .phone((String) r[2])
                        .orderCount(toLong(r[3]))
                        .totalSpent(toDouble(r[4]))
                        .build())
                .toList();
    }

    // MISS-01: Cache top-suppliers for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_TOP_SUPPLIERS,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #limit, #from, #to)")
    public List<TopSupplierResponse> topSuppliers(Long requestedBranchId, int limit, LocalDate from, LocalDate to) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        LocalDate resolvedFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate resolvedTo   = to   != null ? to   : LocalDate.now();
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(resolvedFrom, resolvedTo);

        return reportRepository.topSuppliersRaw(toQueryBranchId(branchId), range.from(), range.to(), limit).stream()
                .map(r -> TopSupplierResponse.builder()
                        .supplierId(toLong(r[0]))
                        .supplierName((String) r[1])
                        .contactNo((String) r[2])
                        .purchaseCount(toLong(r[3]))
                        .totalPurchased(toDouble(r[4]))
                        .build())
                .toList();
    }

    // MISS-01: Cache profit summary for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_PROFIT_SUMMARY,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public ProfitSummaryResponse getProfitSummary(Long requestedBranchId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        Long queryBranchId = toQueryBranchId(branchId);

        // BUG-05 FIX: single-row SQL aggregate instead of loading up to 1,000,000 rows
        // into a Java List and summing them in a for-loop
        ProfitSummaryProjection summary = reportRepository.profitSummaryRaw(queryBranchId, range.from(), range.to());
        double totalRevenue = summary == null ? 0 : toDouble(summary.getTotalRevenue());
        double totalCost    = summary == null ? 0 : toDouble(summary.getTotalCost());
        double grossProfit  = summary == null ? 0 : toDouble(summary.getGrossProfit());

        double totalExpenses = reportRepository.getTotalExpenses(queryBranchId, range.from(), range.to());
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
        double revenue = toDouble(r[5]);
        double profit  = toDouble(r[7]);
        return TopSellingItemResponse.builder()
                .itemId(toLong(r[0]))
                .itemName((String) r[1])
                .itemType(parseItemType(r[2]))
                .qtyUnit(toStr(r[3]))
                .qtySold(toDouble(r[4]))
                .revenue(revenue)
                .cost(toDouble(r[6]))
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

    // DUP-06 FIX: Centralised Object[] row extraction helpers — eliminate the
    // ((Number) r[N]).doubleValue() / .longValue() cast pattern repeated 40+ times.
    private static double toDouble(Object val) {
        return val instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long toLong(Object val) {
        return val instanceof Number n ? n.longValue() : 0L;
    }

    private static String toStr(Object val) {
        return val == null ? null : val.toString();
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

    // ─── Returns Reports ─────────────────────────────────────────────────

    // MISS-01: Cache returns summary for 1 hour
    @Cacheable(value = CacheConfig.CACHE_RPT_RETURNS_SUMMARY,
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#branchId, #from, #to)")
    public ReturnsSummaryResponse getReturnsSummary(Long branchId, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        User user = securityUtils.getCurrentUser();
        Long resolvedBranch = resolveBranchId(user, branchId == null ? 0L : branchId);

        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        LocalDateTime fromDt = range.from();
        LocalDateTime toDt   = range.to();

        long   saleRetCount  = reportRepository.saleReturnCount(resolvedBranch, fromDt, toDt);
        double saleRetTotal  = reportRepository.saleReturnTotal(resolvedBranch, fromDt, toDt);
        long   saleRetItems  = reportRepository.saleReturnItemCount(resolvedBranch, fromDt, toDt);

        long   purRetCount   = reportRepository.purchaseReturnCount(resolvedBranch, fromDt, toDt);
        double purRetTotal   = reportRepository.purchaseReturnTotal(resolvedBranch, fromDt, toDt);
        long   purRetItems   = reportRepository.purchaseReturnItemCount(resolvedBranch, fromDt, toDt);

        double grossSales    = reportRepository.totalSales(resolvedBranch, fromDt, toDt);
        long   totalOrders   = reportRepository.totalOrders(resolvedBranch, fromDt, toDt);

        BigDecimal saleRetBD = BigDecimal.valueOf(saleRetTotal);
        BigDecimal purRetBD  = BigDecimal.valueOf(purRetTotal);
        BigDecimal grossBD   = BigDecimal.valueOf(grossSales);
        BigDecimal netRev    = grossBD.subtract(saleRetBD);
        double returnRate    = totalOrders > 0 ? (saleRetCount * 100.0 / totalOrders) : 0.0;

        return ReturnsSummaryResponse.builder()
                .saleReturnCount(saleRetCount)
                .saleReturnTotal(saleRetBD)
                .saleReturnItemCount(saleRetItems)
                .purchaseReturnCount(purRetCount)
                .purchaseReturnTotal(purRetBD)
                .purchaseReturnItemCount(purRetItems)
                .totalReturnAmount(saleRetBD.add(purRetBD))
                .grossSales(grossBD)
                .netRevenue(netRev)
                .returnRate(Math.round(returnRate * 100.0) / 100.0)
                .build();
    }

    public List<TopReturnedItemResponse> getTopReturnedItems(Long branchId, LocalDate from, LocalDate to, int limit, String type) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranch = resolveBranchId(user, branchId == null ? 0L : branchId);

        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        LocalDateTime fromDt = range.from();
        LocalDateTime toDt   = range.to();

        List<TopReturnedItemResponse> result = new ArrayList<>();

        if ("PURCHASE".equalsIgnoreCase(type)) {
            List<Object[]> rows = reportRepository.topReturnedPurchaseItemsRaw(resolvedBranch, fromDt, toDt, limit);
            for (Object[] r : rows) {
                result.add(TopReturnedItemResponse.builder()
                        .itemId(r[0] != null ? toLong(r[0]) : null)
                        .itemName(r[1] != null ? r[1].toString() : "")
                        .barcode(r[2] != null ? r[2].toString() : null)
                        .returnCount(toLong(r[3]))
                        .totalReturnedQty(toLong(r[4]))
                        .totalReturnAmount(r[5] != null ? new BigDecimal(r[5].toString()) : BigDecimal.ZERO)
                        .build());
            }
        } else {
            List<Object[]> rows = reportRepository.topReturnedSaleItemsRaw(resolvedBranch, fromDt, toDt, limit);
            for (Object[] r : rows) {
                result.add(TopReturnedItemResponse.builder()
                        .itemId(r[0] != null ? toLong(r[0]) : null)
                        .itemName(r[1] != null ? r[1].toString() : "")
                        .barcode(r[2] != null ? r[2].toString() : null)
                        .returnCount(toLong(r[3]))
                        .totalReturnedQty(toLong(r[4]))
                        .totalReturnAmount(r[5] != null ? new BigDecimal(r[5].toString()) : BigDecimal.ZERO)
                        .build());
            }
        }
        return result;
    }

    public List<ReturnReasonBreakdownResponse> getReturnReasonBreakdown(Long branchId, LocalDate from, LocalDate to) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranch = resolveBranchId(user, branchId == null ? 0L : branchId);

        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        LocalDateTime fromDt = range.from();
        LocalDateTime toDt   = range.to();

        List<ReturnReasonBreakdownResponse> result = new ArrayList<>();

        List<Object[]> saleRows = reportRepository.saleReturnReasonBreakdownRaw(resolvedBranch, fromDt, toDt);
        for (Object[] r : saleRows) {
            result.add(ReturnReasonBreakdownResponse.builder()
                    .reason(r[0] != null ? r[0].toString() : "")
                    .count(toLong(r[1]))
                    .totalAmount(r[2] != null ? new BigDecimal(r[2].toString()) : BigDecimal.ZERO)
                    .type("SALE")
                    .build());
        }

        List<Object[]> purRows = reportRepository.purchaseReturnReasonBreakdownRaw(resolvedBranch, fromDt, toDt);
        for (Object[] r : purRows) {
            result.add(ReturnReasonBreakdownResponse.builder()
                    .reason(r[0] != null ? r[0].toString() : "")
                    .count(toLong(r[1]))
                    .totalAmount(r[2] != null ? new BigDecimal(r[2].toString()) : BigDecimal.ZERO)
                    .type("PURCHASE")
                    .build());
        }

        return result;
    }

    public List<ReturnTrendPoint> getReturnTrend(Long branchId, LocalDate from, LocalDate to) {
        User user = securityUtils.getCurrentUser();
        Long resolvedBranch = resolveBranchId(user, branchId == null ? 0L : branchId);

        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        LocalDateTime fromDt = range.from();
        LocalDateTime toDt   = range.to();

        // Use daily trend for ≤ 62 days, monthly otherwise
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(fromDt.toLocalDate(), toDt.toLocalDate());
        List<Object[]> rows = daysBetween <= 62
                ? reportRepository.dailyReturnTrendRaw(resolvedBranch, fromDt, toDt)
                : reportRepository.monthlyReturnTrendRaw(resolvedBranch, fromDt, toDt);

        List<ReturnTrendPoint> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sale = r[1] != null ? new BigDecimal(r[1].toString()) : BigDecimal.ZERO;
            BigDecimal pur  = r[2] != null ? new BigDecimal(r[2].toString()) : BigDecimal.ZERO;
            result.add(ReturnTrendPoint.builder()
                    .label(r[0] != null ? r[0].toString() : "")
                    .saleReturns(sale)
                    .purchaseReturns(pur)
                    .total(sale.add(pur))
                    .build());
        }
        return result;
    }
}
