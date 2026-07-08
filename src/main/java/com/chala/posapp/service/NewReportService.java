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

    // ═══════════════════════════════════════════════════════════════════════
    // RPT-02: Inventory Valuation
    // ═══════════════════════════════════════════════════════════════════════

    @Cacheable(value = "report-inventory-val",
               key = "T(com.chala.posapp.util.CacheKeyUtils).key(#requestedBranchId, #categoryId)")
    public InventoryValuationSummary inventoryValuation(Long requestedBranchId, Long categoryId) {

        User user = securityUtils.getCurrentUser();
        Long branchId = resolveBranchId(user, requestedBranchId);
        long qCat = categoryId != null ? categoryId : 0L;

        List<InventoryValuationResponse> items = reportRepository
                .inventoryValuationRaw(qb(branchId), qCat)
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
        DateRangeUtils.DateTimeRange range = DateRangeUtils.fullDayRange(from, to);
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
            Object[] sales = reportRepository.shiftSalesRaw(
                    cs.getBranchId(), cs.getCashierUserId(), shiftFrom, shiftTo);

            double cashSales   = sales != null ? toDouble(sales[0]) : 0.0;
            double creditSales = sales != null ? toDouble(sales[1]) : 0.0;
            double discount    = sales != null ? toDouble(sales[2]) : 0.0;
            long   orderCount  = sales != null ? toLong(sales[3])   : 0L;
            double expected    = cs.getOpeningCash()
                    + (cs.getCashSales() != null ? cs.getCashSales() : 0.0)
                    - cs.getTotalExpenses() + cs.getTotalCashDrops();

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
        Object[] totals = reportRepository.grnReportTotals(qb(branchId), qSup, range.from(), range.to());

        List<GrnReportResponse> items = rows.stream().map(r -> GrnReportResponse.builder()
                .grnId(toLong(r[0])).grnNo(toStr(r[1]))
                .supplierId(toLong(r[2])).supplierName(toStr(r[3]))
                .branchId(toLong(r[4])).branchName(toStr(r[5]))
                .totalAmount(toDouble(r[6])).paidAmount(toDouble(r[7])).dueAmount(toDouble(r[8]))
                .note(toStr(r[9])).receivedAt(toLdt(r[10])).createdByUsername(toStr(r[11]))
                .build()).toList();

        int totalPages = (int) Math.ceil((double) total / size);
        return GrnReportSummary.builder()
                .page(PageResponse.<GrnReportResponse>builder()
                        .items(items).page(page).size(size)
                        .totalElements(total).totalPages(totalPages).build())
                .totalAmount(totals != null ? toDouble(totals[0]) : 0.0)
                .totalPaid(totals   != null ? toDouble(totals[1]) : 0.0)
                .totalDue(totals    != null ? toDouble(totals[2]) : 0.0)
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
            double open    = toDouble(r[4]);
            double purIn   = toDouble(r[5]);
            double saleOut = toDouble(r[6]);
            double retIn   = toDouble(r[7]);
            double adjNet  = toDouble(r[8]);
            double trfIn   = toDouble(r[9]);
            double trfOut  = toDouble(r[10]);
            return StockMovementResponse.builder()
                    .itemId(toLong(r[0])).barcode(toStr(r[1]))
                    .itemName(toStr(r[2])).unit(toStr(r[3]))
                    .openingStock(open).purchasesIn(purIn).salesOut(saleOut)
                    .returnsIn(retIn).adjustmentsNet(adjNet)
                    .transfersIn(trfIn).transfersOut(trfOut)
                    .closingStock(open + purIn - saleOut + retIn + adjNet + trfIn - trfOut)
                    .build();
        }).toList();

        int totalPages = (int) Math.ceil((double) total / size);
        return PageResponse.<StockMovementResponse>builder()
                .items(items).page(page).size(size)
                .totalElements(total).totalPages(totalPages).build();
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
               key = "T(com.chala.posapp.util.CacheKeyUtils).key('all')")
    public List<CreditAgingResponse> creditAging() {
        return reportRepository.creditAgingRaw().stream()
                .map(r -> CreditAgingResponse.builder()
                        .customerId(toLong(r[0])).customerName(toStr(r[1])).phone(toStr(r[2]))
                        .totalDue(toDouble(r[3])).bucket0to30(toDouble(r[4]))
                        .bucket31to60(toDouble(r[5])).bucket61to90(toDouble(r[6]))
                        .bucket91plus(toDouble(r[7])).oldestOrderAt(toLdt(r[8]))
                        .build())
                .toList();
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
            Long requestedFromBranchId, Long toBranchId,
            String status, LocalDate from, LocalDate to, int page, int size) {

        User user = securityUtils.getCurrentUser();
        Long fromBranchId = resolveBranchId(user, requestedFromBranchId);
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
                fromBranchId, toBranchId, statusEnum,
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
