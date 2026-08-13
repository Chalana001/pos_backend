package com.chala.posapp.service;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.entity.stock.StockTransfer;
import com.chala.posapp.entity.stock.StockTransferStatus;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.repository.*;
import com.chala.posapp.util.CacheKeyUtils;
import com.chala.posapp.util.DateRangeUtils;
import com.chala.posapp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NewReportService {

    private final ReportRepository        reportRepository;
    private final CashShiftRepository     cashShiftRepository;
    private final StockTransferRepository stockTransferRepository;
    private final BranchRepository        branchRepository;
    private final UserRepository          userRepository;
    private final SecurityUtils           securityUtils;

    private Long resolveBranchId(User user, Long requested) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN) return requested;
        return user.getBranchId();
    }

    private static long qb(Long branchId) { return branchId == null ? 0L : branchId; }

    private static double toDouble(Object v) { return v instanceof Number n ? n.doubleValue() : 0.0; }
    private static long toLong(Object v)     { return v instanceof Number n ? n.longValue()   : 0L;  }
    private static String toStr(Object v)    { return v != null ? v.toString() : null; }
    private static LocalDateTime toLdt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    private Map<Long, String> usernameMap(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-01: Cashier Performance
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-cashier-perf",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public List<CashierPerformanceResponse> cashierPerformance(
            Long requestedBranchId, LocalDate from, LocalDate to) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.cashierPerformanceRaw(qb(branchId), range.from(), range.to())
                .stream()
                .map(r -> CashierPerformanceResponse.builder()
                        .cashierUserId(toLong(r[0]))
                        .cashierUsername(toStr(r[1]))
                        .orderCount(toLong(r[2]))
                        .totalSales(toDouble(r[3]))
                        .totalDiscounts(toDouble(r[4]))
                        .avgOrderValue(toDouble(r[5]))
                        .returnCount(toLong(r[6]))
                        .totalRefunds(toDouble(r[7]))
                        .build())
                .toList();
    }

    public List<BranchComparisonResponse> branchComparison(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new BadRequestException("A valid from/to date range is required");
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        return reportRepository.branchComparisonRaw(range.from(), range.to()).stream().map(r -> BranchComparisonResponse.builder()
                .branchId(toLong(r[0])).branchName(toStr(r[1])).orderCount(toLong(r[2])).totalSales(toDouble(r[3]))
                .averageOrderValue(toDouble(r[4])).totalDiscounts(toDouble(r[5])).returnCount(toLong(r[6]))
                .returnAmount(toDouble(r[7])).operatingExpenses(toDouble(r[8])).build()).toList();
    }

    public ExceptionCenterResponse exceptionCenter(Long requestedBranchId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new BadRequestException("A valid from/to date range is required");
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        StockHealthResponse stock = stockHealth(requestedBranchId, 14);
        List<CreditAgingResponse> credit = creditAging(requestedBranchId);
        List<SupplierPayablesAgingResponse> payables = supplierPayablesAging(requestedBranchId);
        List<ExceptionCenterResponse.ExceptionItem> items = new java.util.ArrayList<>();
        stock.getItems().stream().filter(i -> java.util.Set.of("NEGATIVE","MISSING_COST","BELOW_COST","EXPIRED").contains(i.getStatus())).limit(50).forEach(i -> items.add(ExceptionCenterResponse.ExceptionItem.builder()
                .type(i.getStatus()).severity("NEGATIVE".equals(i.getStatus()) || "EXPIRED".equals(i.getStatus()) ? "CRITICAL" : "HIGH")
                .title(i.getItemName()).detail(i.getStatus().replace('_',' ')).amount(Math.abs(i.getStockValue())).path("/reports/stock-health").build()));
        credit.stream().filter(c -> c.getBucket91plus() > 0).forEach(c -> items.add(ExceptionCenterResponse.ExceptionItem.builder().type("CUSTOMER_CREDIT_91_PLUS").severity("CRITICAL").title(c.getCustomerName()).detail("Customer credit overdue 91+ days").amount(c.getBucket91plus()).path("/reports/credit-aging").build()));
        payables.stream().filter(s -> s.getBucket91plus() > 0).forEach(s -> items.add(ExceptionCenterResponse.ExceptionItem.builder().type("SUPPLIER_PAYABLE_91_PLUS").severity("CRITICAL").title(s.getSupplierName()).detail("Supplier payable overdue 91+ days").amount(s.getBucket91plus()).path("/reports/supplier-payables").build()));
        reportRepository.exceptionActivityRaw(qb(branchId), range.from(), range.to(), LocalDateTime.now().minusHours(12)).forEach(r -> {
            String type = toStr(r[0]);
            String path = "HIGH_DISCOUNT".equals(type) ? "/reports/sales" : "LARGE_RETURN".equals(type) ? "/reports/returns" : "CASH_SHORTAGE".equals(type) || "STALE_SHIFT".equals(type) ? "/reports/shifts" : "/stock/adjustments";
            items.add(ExceptionCenterResponse.ExceptionItem.builder().type(type).title(toStr(r[1])).detail(toStr(r[2])).amount(toDouble(r[3])).severity(toStr(r[4])).path(path).build());
        });
        long critical = items.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count();
        return ExceptionCenterResponse.builder().totalExceptions(items.size()).criticalExceptions(critical).items(items).build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-02: Inventory Valuation
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-inventory-val",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #categoryId, #subCategoryId)")
    public InventoryValuationSummary inventoryValuation(Long requestedBranchId, Long categoryId, Long subCategoryId) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        long qCat = categoryId != null ? categoryId : 0L;
        long qSubCat = subCategoryId != null ? subCategoryId : 0L;

        List<InventoryValuationResponse> items = reportRepository
                .inventoryValuationRaw(qb(branchId), qCat, qSubCat)
                .stream()
                .map(r -> {
                    double sv = toDouble(r[9]);
                    double pr = toDouble(r[11]);
                    return InventoryValuationResponse.builder()
                            .itemId(toLong(r[0])).barcode(toStr(r[1])).itemName(toStr(r[2]))
                            .categoryName(toStr(r[3])).subCategoryName(toStr(r[4]))
                            .itemType(toStr(r[5])).unit(toStr(r[6]))
                            .qtyOnHand(toDouble(r[7])).costPrice(toDouble(r[8]))
                            .stockValue(sv).sellingPrice(toDouble(r[10]))
                            .potentialRevenue(pr).potentialProfit(pr - sv)
                            .build();
                })
                .toList();

        return InventoryValuationSummary.builder()
                .items(items)
                .totalStockValue(items.stream().mapToDouble(InventoryValuationResponse::getStockValue).sum())
                .totalPotentialRevenue(items.stream().mapToDouble(InventoryValuationResponse::getPotentialRevenue).sum())
                .totalPotentialProfit(items.stream().mapToDouble(InventoryValuationResponse::getPotentialProfit).sum())
                .totalItems(items.size())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-03: Shift Summary / Z-Report
    // ═══════════════════════════════════════════════════════════════════════

    public PageResponse<ShiftSummaryResponse> shiftSummary(
            Long requestedBranchId, Long cashierUserId,
            LocalDate from, LocalDate to, int page, int size) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(365);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(resolvedFrom, resolvedTo);
        Long effectiveCashierId = (user.getRole() == Role.CASHIER) ? user.getId() : cashierUserId;

        Page<CashShift> shiftPage = cashShiftRepository.findShiftsForReport(
                branchId, effectiveCashierId, range.from(), range.to(),
                PageRequest.of(page, size, Sort.by("openedAt").descending()));

        List<Long> cashierIds = shiftPage.getContent().stream()
                .map(CashShift::getCashierUserId).distinct().toList();
        Map<Long, String> uMap = usernameMap(cashierIds);

        List<ShiftSummaryResponse> responses = shiftPage.getContent().stream().map(cs -> {
            LocalDateTime shiftFrom = cs.getOpenedAt();
            LocalDateTime shiftTo   = cs.getClosedAt() != null ? cs.getClosedAt() : LocalDateTime.now();
            List<Object[]> salesRows = reportRepository.shiftSalesRaw(
                    cs.getBranchId(), cs.getCashierUserId(), shiftFrom, shiftTo);
            Object[] sales = salesRows.isEmpty() ? null : salesRows.get(0);

            double cashSales   = sales != null ? toDouble(sales[0]) : 0.0;
            double creditSales = sales != null ? toDouble(sales[1]) : 0.0;
            double discount    = sales != null ? toDouble(sales[2]) : 0.0;
            long   orderCount  = sales != null ? toLong(sales[3])   : 0L;
            double expected = cs.getExpectedCash() != null
                    ? cs.getExpectedCash()
                    : cs.getOpeningCash() + cashSales - cs.getTotalExpenses() - cs.getTotalCashDrops();

            return ShiftSummaryResponse.builder()
                    .shiftId(cs.getId()).branchId(cs.getBranchId())
                    .cashierUserId(cs.getCashierUserId())
                    .cashierUsername(uMap.getOrDefault(cs.getCashierUserId(), "unknown"))
                    .shiftStatus(cs.getStatus().name())
                    .openedAt(cs.getOpenedAt()).closedAt(cs.getClosedAt())
                    .cashSales(cashSales).creditSales(creditSales)
                    .totalSales(cashSales + creditSales).totalDiscount(discount)
                    .orderCount(orderCount).openingCash(cs.getOpeningCash())
                    .totalCashDrops(cs.getTotalCashDrops()).totalExpenses(cs.getTotalExpenses())
                    .expectedClosingCash(expected)
                    .countedCash(cs.getCountedCash()     != null ? cs.getCountedCash()     : 0.0)
                    .cashDifference(cs.getCashDifference() != null ? cs.getCashDifference() : 0.0)
                    .openNote(cs.getOpenNote()).closeNote(cs.getCloseNote())
                    .build();
        }).toList();

        return PageResponse.<ShiftSummaryResponse>builder()
                .items(responses).page(page).size(size)
                .totalElements(shiftPage.getTotalElements()).totalPages(shiftPage.getTotalPages())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-04: GRN / Purchase Report
    // ═══════════════════════════════════════════════════════════════════════

    public CashFlowResponse cashFlow(Long requestedBranchId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BadRequestException("A valid from/to date range is required");
        }
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        List<Object[]> totalsRows = reportRepository.cashFlowTotalsRaw(qb(branchId), range.from(), range.to());
        Object[] totals = totalsRows.isEmpty() ? new Object[7] : totalsRows.get(0);
        double cashSales = toDouble(totals[0]);
        double collections = toDouble(totals[1]);
        double expenses = toDouble(totals[2]);
        double purchases = toDouble(totals[3]);
        double supplierPayments = toDouble(totals[4]);
        double refunds = toDouble(totals[5]);
        double drops = toDouble(totals[6]);
        double inflows = cashSales + collections;
        double outflows = expenses + purchases + supplierPayments + refunds;

        List<CashFlowResponse.DailyMovement> daily = reportRepository
                .cashFlowDailyRaw(qb(branchId), range.from(), range.to()).stream()
                .map(row -> {
                    double dayInflows = toDouble(row[1]);
                    double dayOutflows = toDouble(row[2]);
                    return CashFlowResponse.DailyMovement.builder()
                            .date(row[0] instanceof Date date ? date.toLocalDate() : LocalDate.parse(row[0].toString()))
                            .inflows(dayInflows).outflows(dayOutflows)
                            .netMovement(dayInflows - dayOutflows).build();
                }).toList();

        return CashFlowResponse.builder()
                .cashSales(cashSales).creditCollections(collections).totalInflows(inflows)
                .expenses(expenses).purchasePayments(purchases).supplierPayments(supplierPayments)
                .cashRefunds(refunds).totalOutflows(outflows).netCashMovement(inflows - outflows)
                .cashDrops(drops).dailyMovements(daily).build();
    }

    public ProfitAndLossResponse profitAndLoss(Long requestedBranchId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BadRequestException("A valid from/to date range is required");
        }
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate comparisonTo = from.minusDays(1);
        LocalDate comparisonFrom = comparisonTo.minusDays(periodDays - 1);

        return ProfitAndLossResponse.builder()
                .currentPeriod(ProfitAndLossResponse.Period.builder().from(from).to(to).build())
                .comparisonPeriod(ProfitAndLossResponse.Period.builder().from(comparisonFrom).to(comparisonTo).build())
                .current(profitAndLossStatement(branchId, from, to))
                .comparison(profitAndLossStatement(branchId, comparisonFrom, comparisonTo))
                .build();
    }

    private ProfitAndLossResponse.Statement profitAndLossStatement(Long branchId, LocalDate from, LocalDate to) {
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        List<Object[]> rows = reportRepository.profitAndLossRaw(qb(branchId), range.from(), range.to());
        Object[] row = rows.isEmpty() ? new Object[8] : rows.get(0);
        double itemRevenue = toDouble(row[0]);
        double billDiscounts = toDouble(row[1]);
        double salesReturns = toDouble(row[2]);
        double originalCost = toDouble(row[3]);
        double returnedCost = toDouble(row[4]);
        double expenses = toDouble(row[5]);
        long revenueLines = toLong(row[6]);
        long missingCostLines = toLong(row[7]);
        double netRevenue = itemRevenue - billDiscounts - salesReturns;
        double cogs = originalCost - returnedCost;
        double grossProfit = netRevenue - cogs;
        double netProfit = grossProfit - expenses;

        return ProfitAndLossResponse.Statement.builder()
                .itemRevenue(itemRevenue).billDiscounts(billDiscounts).salesReturns(salesReturns)
                .netRevenue(netRevenue).costOfGoodsSold(cogs).returnedCost(returnedCost)
                .grossProfit(grossProfit).grossMarginPercent(netRevenue == 0 ? 0 : grossProfit / netRevenue * 100)
                .operatingExpenses(expenses).netProfit(netProfit)
                .netMarginPercent(netRevenue == 0 ? 0 : netProfit / netRevenue * 100)
                .revenueLineCount(revenueLines).missingCostLineCount(missingCostLines)
                .costCoveragePercent(revenueLines == 0 ? 100 : (revenueLines - missingCostLines) * 100.0 / revenueLines)
                .build();
    }

    public GrnReportSummary grnReport(
            Long requestedBranchId, Long supplierId,
            LocalDate from, LocalDate to, int page, int size) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        long qSup = supplierId != null ? supplierId : 0L;

        List<Object[]> rows = reportRepository.grnReportRaw(
                qb(branchId), qSup, range.from(), range.to(), size, page * size);
        long total      = reportRepository.countGrnReport(qb(branchId), qSup, range.from(), range.to());
        List<Object[]> totalsRows = reportRepository.grnReportTotals(qb(branchId), qSup, range.from(), range.to());
        Object[] totals = totalsRows.isEmpty() ? null : totalsRows.get(0);

        List<GrnReportResponse> items = rows.stream().map(r -> GrnReportResponse.builder()
                .grnId(toLong(r[0])).grnNo(toStr(r[1]))
                .supplierId(toLong(r[2])).supplierName(toStr(r[3]))
                .branchId(toLong(r[4])).branchName(toStr(r[5]))
                .totalAmount(toDouble(r[6])).paidAmount(toDouble(r[7])).dueAmount(toDouble(r[8]))
                .note(toStr(r[9])).receivedAt(toLdt(r[10])).createdByUsername(toStr(r[11]))
                .purchaseId(r[12] == null ? null : toLong(r[12])).purchaseInvoiceNo(toStr(r[13]))
                .purchaseStatus(toStr(r[14])).purchasePaidAmount(toDouble(r[15])).purchaseDueAmount(toDouble(r[16]))
                .returnAmount(toDouble(r[17])).netReceivedAmount(toDouble(r[6]) - toDouble(r[17]))
                .build()).toList();

        int totalPages = (int) Math.ceil((double) total / size);
        return GrnReportSummary.builder()
                .page(PageResponse.<GrnReportResponse>builder()
                        .items(items).page(page).size(size)
                        .totalElements(total).totalPages(totalPages).build())
                .totalAmount(totals != null ? toDouble(totals[0]) : 0.0)
                .totalPaid(totals   != null ? toDouble(totals[1]) : 0.0)
                .totalDue(totals    != null ? toDouble(totals[2]) : 0.0)
                .totalReturns(totals != null ? toDouble(totals[3]) : 0.0)
                .netReceivedAmount(totals != null ? toDouble(totals[0]) - toDouble(totals[3]) : 0.0)
                .uniquePurchaseCount(totals != null ? toLong(totals[4]) : 0L)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-05: Stock Movement
    // ═══════════════════════════════════════════════════════════════════════

    public PageResponse<StockMovementResponse> stockMovement(
            Long requestedBranchId, LocalDate from, LocalDate to, int page, int size) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        List<Object[]> rows = reportRepository.stockMovementRaw(
                qb(branchId), range.from(), range.to(), size, page * size);
        long total = reportRepository.countStockMovement();

        List<StockMovementResponse> items = rows.stream().map(r -> {
            boolean scaled = "NORMAL".equals(toStr(r[4])) || "WEIGHT".equals(toStr(r[4])) || "VOLUME".equals(toStr(r[4]));
            double factor = scaled ? 1000.0 : 1.0;
            double open = toDouble(r[5]) / factor, purIn = toDouble(r[6]) / factor;
            double saleOut = toDouble(r[7]) / factor, retIn = toDouble(r[8]) / factor;
            double purRetOut = toDouble(r[9]) / factor, adjNet = toDouble(r[10]) / factor;
            double trfIn = toDouble(r[11]) / factor, trfOut = toDouble(r[12]) / factor;
            double processingIn = toDouble(r[13]) / factor, processingOut = toDouble(r[14]) / factor;
            return StockMovementResponse.builder()
                    .itemId(toLong(r[0])).barcode(toStr(r[1]))
                    .itemName(toStr(r[2])).unit(toStr(r[3]))
                    .openingStock(open).purchasesIn(purIn).salesOut(saleOut)
                    .returnsIn(retIn).purchaseReturnsOut(purRetOut).adjustmentsNet(adjNet)
                    .transfersIn(trfIn).transfersOut(trfOut)
                    .processingIn(processingIn).processingOut(processingOut)
                    .closingStock(open + purIn - saleOut + retIn - purRetOut + adjNet + trfIn - trfOut + processingIn - processingOut)
                    .build();
        }).toList();

        int totalPages = (int) Math.ceil((double) total / size);
        return PageResponse.<StockMovementResponse>builder()
                .items(items).page(page).size(size)
                .totalElements(total).totalPages(totalPages).build();
    }

    public StockHealthResponse stockHealth(Long requestedBranchId, int targetCoverDays) {
        if (targetCoverDays < 1 || targetCoverDays > 90) throw new BadRequestException("targetCoverDays must be between 1 and 90");
        final int salesWindowDays = 90;
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        LocalDateTime salesFrom = LocalDate.now().minusDays(salesWindowDays).atStartOfDay();
        LocalDate today = LocalDate.now();
        List<StockHealthResponse.ItemHealth> items = reportRepository.stockHealthRaw(qb(branchId), salesFrom).stream().map(r -> {
            boolean scaled = "NORMAL".equals(toStr(r[4])) || "WEIGHT".equals(toStr(r[4])) || "VOLUME".equals(toStr(r[4]));
            double factor = scaled ? 1000.0 : 1.0;
            double stock = toDouble(r[5]) / factor;
            double reorder = toDouble(r[6]) / factor;
            double cost = toDouble(r[7]);
            double selling = toDouble(r[8]);
            double sold = toDouble(r[9]) / factor;
            double daily = sold / salesWindowDays;
            double suggested = Math.max(0, Math.max(reorder, daily * targetCoverDays) - stock);
            LocalDateTime lastSold = toLdt(r[10]);
            LocalDate expiry = r[12] == null ? null : toLdt(r[12]).toLocalDate();
            String status = stock < 0 ? "NEGATIVE" : stock == 0 ? "OUT_OF_STOCK" : expiry != null && expiry.isBefore(today) ? "EXPIRED" : expiry != null && !expiry.isAfter(today.plusDays(30)) ? "EXPIRING_SOON" : lastSold == null || lastSold.isBefore(today.minusDays(90).atStartOfDay()) ? "DEAD_STOCK" : stock <= reorder ? "LOW_STOCK" : selling < cost ? "BELOW_COST" : cost <= 0 ? "MISSING_COST" : "HEALTHY";
            return StockHealthResponse.ItemHealth.builder().itemId(toLong(r[0])).barcode(toStr(r[1])).itemName(toStr(r[2])).unit(toStr(r[3]))
                    .qtyOnHand(stock).reorderLevel(reorder).costPrice(cost).sellingPrice(selling).stockValue(stock * cost)
                    .soldLast90Days(sold).averageDailySales(daily).estimatedDaysOfStock(daily > 0 ? stock / daily : null)
                    .suggestedReorderQty(suggested).estimatedReorderCost(suggested * cost).lastSoldAt(lastSold)
                    .preferredSupplier(toStr(r[11])).nearestExpiryDate(expiry).status(status).build();
        }).toList();
        return StockHealthResponse.builder().salesWindowDays(salesWindowDays).targetCoverDays(targetCoverDays).totalItems(items.size())
                .outOfStockItems(items.stream().filter(i -> "OUT_OF_STOCK".equals(i.getStatus())).count())
                .negativeStockItems(items.stream().filter(i -> "NEGATIVE".equals(i.getStatus())).count())
                .belowReorderItems(items.stream().filter(i -> i.getQtyOnHand() > 0 && i.getQtyOnHand() <= i.getReorderLevel()).count())
                .deadStockItems(items.stream().filter(i -> "DEAD_STOCK".equals(i.getStatus())).count())
                .itemsWithExpiredStock(items.stream().filter(i -> "EXPIRED".equals(i.getStatus())).count())
                .itemsExpiringSoon(items.stream().filter(i -> "EXPIRING_SOON".equals(i.getStatus())).count())
                .deadStockValue(items.stream().filter(i -> "DEAD_STOCK".equals(i.getStatus())).mapToDouble(StockHealthResponse.ItemHealth::getStockValue).sum())
                .estimatedReorderCost(items.stream().mapToDouble(StockHealthResponse.ItemHealth::getEstimatedReorderCost).sum()).items(items).build();
    }

    public DemandForecastResponse demandForecast(Long requestedBranchId, int forecastDays, int targetCoverDays) {
        return demandForecast(requestedBranchId, forecastDays, targetCoverDays, null, null, null, null, false);
    }

    public DemandForecastResponse demandForecast(Long requestedBranchId, int forecastDays, int targetCoverDays,
                                                 Long categoryId, Long subCategoryId, Long supplierId, String confidence,
                                                 boolean actionableOnly) {
        if (forecastDays < 7 || forecastDays > 90) throw new BadRequestException("forecastDays must be between 7 and 90");
        if (targetCoverDays < 1 || targetCoverDays > 90) throw new BadRequestException("targetCoverDays must be between 1 and 90");
        String normalizedConfidence = confidence == null || confidence.isBlank() ? null : confidence.trim().toUpperCase();
        if (normalizedConfidence != null && !java.util.Set.of("HIGH", "MEDIUM", "LOW", "INSUFFICIENT").contains(normalizedConfidence)) {
            throw new BadRequestException("confidence must be HIGH, MEDIUM, LOW, or INSUFFICIENT");
        }
        final int historyDays = 90;
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        LocalDateTime recentFrom = LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime historyFrom = LocalDate.now().minusDays(historyDays).atStartOfDay();
        Map<Long, List<Double>> dailyDemandByItem = reportRepository.dailyItemDemandRaw(qb(branchId), historyFrom).stream()
                .collect(Collectors.groupingBy(row -> toLong(row[0]), Collectors.mapping(row -> toDouble(row[2]), Collectors.toList())));
        final String confidenceFilter = normalizedConfidence;
        List<DemandForecastResponse.ItemForecast> items = reportRepository.demandForecastRaw(qb(branchId), historyFrom, recentFrom, categoryId, subCategoryId, supplierId)
                .stream().map(row -> forecastItem(row, forecastDays, targetCoverDays, dailyDemandByItem.getOrDefault(toLong(row[0]), List.of())))
                .filter(item -> confidenceFilter == null || confidenceFilter.equals(item.getConfidence()))
                .filter(item -> !actionableOnly || item.getSuggestedReorderQty() > 0)
                .toList();
        return DemandForecastResponse.builder()
                .historyDays(historyDays).forecastDays(forecastDays).targetCoverDays(targetCoverDays)
                .totalItems(items.size())
                .actionableItems(items.stream().filter(item -> item.getSuggestedReorderQty() > 0).count())
                .lowConfidenceItems(items.stream().filter(item -> !"HIGH".equals(item.getConfidence())).count())
                .categoryId(categoryId).supplierId(supplierId).confidenceFilter(confidenceFilter).actionableOnly(actionableOnly)
                .projectedRevenue(items.stream().mapToDouble(DemandForecastResponse.ItemForecast::getProjectedRevenue).sum())
                .estimatedReorderCost(items.stream().mapToDouble(DemandForecastResponse.ItemForecast::getEstimatedReorderCost).sum())
                .items(items).build();
    }

    private DemandForecastResponse.ItemForecast forecastItem(Object[] row, int forecastDays, int targetCoverDays, List<Double> dailyDemandValues) {
        boolean scaled = "NORMAL".equals(toStr(row[4])) || "WEIGHT".equals(toStr(row[4])) || "VOLUME".equals(toStr(row[4]));
        double factor = scaled ? 1000.0 : 1.0;
        double stock = toDouble(row[5]) / factor;
        double cost = toDouble(row[6]);
        double selling = toDouble(row[7]);
        double reorderLevel = toDouble(row[8]) / factor;
        double recentSold = toDouble(row[9]) / factor;
        double previousSold = toDouble(row[10]) / factor;
        int activeDays = (int) toLong(row[11]);
        double recentDaily = recentSold / 30.0;
        double previousDaily = previousSold / 60.0;
        double dailyDemand = recentSold > 0 ? recentDaily * 0.7 + previousDaily * 0.3 : 0;
        double rawMean = dailyDemandValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyDemandValues.stream().mapToDouble(value -> Math.pow(value - rawMean, 2)).average().orElse(0);
        double coefficientOfVariation = rawMean <= 0 ? 0 : Math.sqrt(variance) / rawMean;
        String confidence;
        String warning;
        String trend = recentSold <= 0 ? "NO_RECENT_DEMAND" : previousDaily <= 0 ? "NEW_DEMAND" : recentDaily >= previousDaily * 1.2 ? "RISING" : recentDaily <= previousDaily * 0.8 ? "FALLING" : "STABLE";
        if (activeDays < 5 || recentSold + previousSold <= 0) {
            confidence = "INSUFFICIENT";
            warning = "Fewer than 5 selling days in the 90-day history; use the configured reorder level.";
        } else if (recentSold <= 0) {
            confidence = "LOW";
            warning = "No sales in the last 30 days; older demand is not projected forward.";
        } else if (activeDays >= 20 && coefficientOfVariation <= 1.0) {
            confidence = "HIGH";
            warning = null;
        } else if (activeDays >= 10 && coefficientOfVariation <= 1.5) {
            confidence = "MEDIUM";
            warning = "Demand history is limited or variable; review before ordering.";
        } else {
            confidence = "LOW";
            warning = "Demand is sparse or volatile; forecast is directional only.";
        }
        double projectedDemand = "INSUFFICIENT".equals(confidence) ? 0 : dailyDemand * forecastDays;
        double reorderLevelGap = Math.max(0, reorderLevel - stock);
        double suggested = "INSUFFICIENT".equals(confidence) ? 0 : Math.max(0, dailyDemand * targetCoverDays - stock);
        return DemandForecastResponse.ItemForecast.builder()
                .itemId(toLong(row[0])).barcode(toStr(row[1])).itemName(toStr(row[2])).unit(toStr(row[3]))
                .qtyOnHand(stock).soldLast30Days(recentSold).soldPrevious60Days(previousSold).activeSalesDays(activeDays)
                .averageDailyDemand(dailyDemand).projectedDemand(projectedDemand).projectedRevenue(projectedDemand * selling)
                .estimatedStockoutDays(dailyDemand > 0 ? Math.max(0, stock) / dailyDemand : null)
                .suggestedReorderQty(suggested).estimatedReorderCost(suggested * cost)
                .reorderLevelGapQty(reorderLevelGap).trend(trend).confidence(confidence).warning(warning).build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-06: Expense Report by Category
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-expenses",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public ExpenseReportSummary expenseReport(Long requestedBranchId, LocalDate from, LocalDate to) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        List<ExpenseReportResponse> byCategory = reportRepository
                .expensesByCategoryRaw(qb(branchId), range.from(), range.to())
                .stream()
                .map(r -> ExpenseReportResponse.builder()
                        .category(toStr(r[0])).expenseTypeId(toLong(r[1]))
                        .count(toLong(r[2])).totalAmount(toDouble(r[3])).avgAmount(toDouble(r[4]))
                        .build())
                .toList();

        Object[] totals = reportRepository.expenseTotalsRaw(qb(branchId), range.from(), range.to());
        return ExpenseReportSummary.builder()
                .byCategory(byCategory)
                .grandTotal(totals != null ? toDouble(totals[0]) : 0.0)
                .totalCount(totals  != null ? toLong(totals[1])  : 0L)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-07: Customer Credit Aging
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-credit-aging",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId)")
    public List<CreditAgingResponse> creditAging(Long requestedBranchId) {
        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        return reportRepository.creditAgingRaw(qb(branchId)).stream()
                .map(r -> {
                    double totalDue = toDouble(r[3]);
                    double overdue91 = toDouble(r[7]);
                    Double creditLimit = r[12] == null ? null : toDouble(r[12]);
                    String priority = overdue91 > 0 ? "CRITICAL" : toDouble(r[6]) > 0 ? "HIGH" : toDouble(r[5]) > 0 ? "MEDIUM" : "NORMAL";
                    return CreditAgingResponse.builder()
                            .customerId(toLong(r[0])).customerName(toStr(r[1])).phone(toStr(r[2]))
                            .totalDue(totalDue).bucket0to30(toDouble(r[4])).bucket31to60(toDouble(r[5]))
                            .bucket61to90(toDouble(r[6])).bucket91plus(overdue91).oldestOrderAt(toLdt(r[8]))
                            .oldestInvoiceNo(toStr(r[9])).unpaidInvoiceCount(toLong(r[10])).lastPaymentAt(toLdt(r[11]))
                            .creditLimit(creditLimit).overCreditLimit(creditLimit != null && totalDue > creditLimit)
                            .priority(priority).build();
                })
                .toList();
    }

    public List<SupplierPayablesAgingResponse> supplierPayablesAging(Long requestedBranchId) {
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        return reportRepository.supplierPayablesAgingRaw(qb(branchId)).stream()
                .map(r -> {
                    double bucket31 = toDouble(r[5]);
                    double bucket61 = toDouble(r[6]);
                    double bucket91 = toDouble(r[7]);
                    String priority = bucket91 > 0 ? "CRITICAL" : bucket61 > 0 ? "HIGH" : bucket31 > 0 ? "MEDIUM" : "NORMAL";
                    return SupplierPayablesAgingResponse.builder()
                            .supplierId(toLong(r[0])).supplierName(toStr(r[1])).contactNo(toStr(r[2]))
                            .totalDue(toDouble(r[3])).bucket0to30(toDouble(r[4])).bucket31to60(bucket31)
                            .bucket61to90(bucket61).bucket91plus(bucket91).oldestPurchaseAt(toLdt(r[8]))
                            .oldestInvoiceNo(toStr(r[9])).unpaidPurchaseCount(toLong(r[10]))
                            .lastPaymentAt(toLdt(r[11])).priority(priority).build();
                }).toList();
    }

    public CustomerBehaviorResponse customerBehavior(Long requestedBranchId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new BadRequestException("A valid from/to date range is required");
        Long branchId = resolveBranchId(securityUtils.getCurrentUser(), requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
        List<CustomerBehaviorResponse.CustomerBehavior> customers = reportRepository.customerBehaviorRaw(qb(branchId), range.from(), range.to()).stream().map(r -> {
            LocalDateTime first = toLdt(r[3]);
            LocalDateTime last = toLdt(r[4]);
            long periodOrders = toLong(r[5]);
            long daysSince = java.time.temporal.ChronoUnit.DAYS.between(last.toLocalDate(), to);
            String bucket = daysSince <= 30 ? "ACTIVE_30" : daysSince <= 60 ? "INACTIVE_31_60" : daysSince <= 90 ? "INACTIVE_61_90" : "INACTIVE_91_PLUS";
            return CustomerBehaviorResponse.CustomerBehavior.builder().customerId(toLong(r[0])).customerName(toStr(r[1])).phone(toStr(r[2]))
                    .firstPurchaseAt(first).lastPurchaseAt(last).periodOrderCount(periodOrders).lifetimeOrderCount(toLong(r[6]))
                    .periodSpend(toDouble(r[7])).lifetimeSpend(toDouble(r[8])).averagePeriodOrder(periodOrders == 0 ? 0 : toDouble(r[7]) / periodOrders)
                    .currentDue(toDouble(r[9])).daysSinceLastPurchase(daysSince).inactivityBucket(bucket)
                    .newCustomer(!first.isBefore(range.from())).build();
        }).toList();
        long active = customers.size();
        long newCustomers = customers.stream().filter(CustomerBehaviorResponse.CustomerBehavior::isNewCustomer).count();
        long returning = active - newCustomers;
        long orders = customers.stream().mapToLong(CustomerBehaviorResponse.CustomerBehavior::getPeriodOrderCount).sum();
        return CustomerBehaviorResponse.builder().activeCustomersInPeriod(active).newCustomers(newCustomers).returningCustomers(returning)
                .repeatRatePercent(active == 0 ? 0 : returning * 100.0 / active).periodOrders(orders)
                .averageOrdersPerActiveCustomer(active == 0 ? 0 : orders * 1.0 / active).customers(customers).build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-08: Promotion Effectiveness
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-promotion-eff",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public List<PromotionEffectivenessResponse> promotionEffectiveness(
            Long requestedBranchId, LocalDate from, LocalDate to) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        return reportRepository.promotionEffectivenessRaw(qb(branchId), range.from(), range.to())
                .stream()
                .map(r -> PromotionEffectivenessResponse.builder()
                        .promotionId(toLong(r[0])).promotionName(toStr(r[1]))
                        .discountType(toStr(r[2])).discountValue(toDouble(r[3]))
                        .timesApplied(toLong(r[4])).totalDiscountGiven(toDouble(r[5]))
                        .totalRevenue(toDouble(r[6])).avgOrderValue(toDouble(r[7]))
                        .build())
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-09: Warranty Report
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-warranty",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #from, #to)")
    public WarrantyReportSummary warrantyReport(Long requestedBranchId, LocalDate from, LocalDate to) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        List<WarrantyReportResponse> items = reportRepository
                .warrantyReportRaw(qb(branchId), range.from(), range.to())
                .stream()
                .map(r -> WarrantyReportResponse.builder()
                        .itemId(toLong(r[0])).itemName(toStr(r[1])).barcode(toStr(r[2]))
                        .totalWarranties(toLong(r[3])).activeCount(toLong(r[4]))
                        .claimedCount(toLong(r[5])).expiredCount(toLong(r[6])).voidCount(toLong(r[7]))
                        .build())
                .toList();

        return WarrantyReportSummary.builder()
                .items(items)
                .totalWarranties(items.stream().mapToLong(WarrantyReportResponse::getTotalWarranties).sum())
                .totalActive(items.stream().mapToLong(WarrantyReportResponse::getActiveCount).sum())
                .totalClaimed(items.stream().mapToLong(WarrantyReportResponse::getClaimedCount).sum())
                .totalExpired(items.stream().mapToLong(WarrantyReportResponse::getExpiredCount).sum())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-10: Stock Transfer Report
    // ═══════════════════════════════════════════════════════════════════════

    public PageResponse<StockTransferReportResponse> stockTransferReport(
            Long requestedBranchId, Long requestedFromBranchId, Long toBranchId,
            String status, LocalDate from, LocalDate to, int page, int size) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        Long fromBranchId = requestedFromBranchId;
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);

        StockTransferStatus statusEnum = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                statusEnum = StockTransferStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid transfer status: " + status);
            }
        }

        Page<StockTransfer> transferPage = stockTransferRepository.findForReport(
                branchId, fromBranchId, toBranchId, statusEnum,
                range.from(), range.to(),
                PageRequest.of(page, size, Sort.by("requestedAt").descending()));

        List<Long> userIds = transferPage.getContent().stream()
                .map(StockTransfer::getRequestedByUserId).distinct().toList();
        Map<Long, String> uMap = usernameMap(userIds);

        Map<Long, String> branchNames = branchRepository.findAll().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName, (a, b) -> a));

        List<StockTransferReportResponse> responses = transferPage.getContent().stream().map(t -> {
            List<StockTransferReportResponse.TransferItemLine> lines = t.getItems().stream()
                    .map(ti -> StockTransferReportResponse.TransferItemLine.builder()
                            .itemId(ti.getItemId()).itemName(ti.getItemName()).barcode(ti.getBarcode())
                            .quantity(ti.getDisplayQty() != null
                                    ? ti.getDisplayQty().doubleValue()
                                    : (double) ti.getQty())
                            .unit(ti.getQtyUnit() != null ? ti.getQtyUnit().name() : null)
                            .build())
                    .toList();
            return StockTransferReportResponse.builder()
                    .transferId(t.getId()).transferNo(t.getTransferNo())
                    .fromBranchId(t.getFromBranchId())
                    .fromBranchName(branchNames.getOrDefault(t.getFromBranchId(), ""))
                    .toBranchId(t.getToBranchId())
                    .toBranchName(branchNames.getOrDefault(t.getToBranchId(), ""))
                    .status(t.getStatus().name()).createdAt(t.getRequestedAt())
                    .createdByUsername(uMap.getOrDefault(t.getRequestedByUserId(), "unknown"))
                    .items(lines)
                    .build();
        }).toList();

        return PageResponse.<StockTransferReportResponse>builder()
                .items(responses).page(page).size(size)
                .totalElements(transferPage.getTotalElements())
                .totalPages(transferPage.getTotalPages())
                .build();
    }
}
