package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.service.NewReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Part 5 - New report endpoints (RPT-01 through RPT-10).
 * All routes live under /api/reports/v2 to avoid conflicts with ReportController.
 */
@RestController
@RequestMapping("/api/reports/v2")
@RequiredArgsConstructor
public class NewReportController {

    private final NewReportService reportService;

    // RPT-01: Cashier Performance
    @GetMapping("/cashier-performance")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<List<CashierPerformanceResponse>> cashierPerformance(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.cashierPerformance(branchId, from, to));
    }

    @GetMapping("/branch-comparison")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<BranchComparisonResponse>> branchComparison(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.branchComparison(from, to));
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<ExceptionCenterResponse> exceptionCenter(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.exceptionCenter(branchId, from, to));
    }

    // RPT-02: Inventory Valuation
    @GetMapping("/inventory-valuation")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<InventoryValuationSummary> inventoryValuation(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(reportService.inventoryValuation(branchId, categoryId));
    }

    // RPT-03: Shift Summary / Z-Report
    @GetMapping("/shift-summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER','CASHIER')")
    public ResponseEntity<PageResponse<ShiftSummaryResponse>> shiftSummary(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long cashierUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reportService.shiftSummary(branchId, cashierUserId, from, to, page, size));
    }

    // RPT-04: GRN / Purchase Report
    @GetMapping("/profit-loss")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<ProfitAndLossResponse> profitAndLoss(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.profitAndLoss(branchId, from, to));
    }

    // RPT-04: GRN / Purchase Report
    @GetMapping("/cash-flow")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<CashFlowResponse> cashFlow(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.cashFlow(branchId, from, to));
    }

    // RPT-04: GRN / Purchase Report
    @GetMapping("/grn")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<GrnReportSummary> grnReport(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reportService.grnReport(branchId, supplierId, from, to, page, size));
    }

    // RPT-05: Stock Movement
    @GetMapping("/stock-movement")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<PageResponse<StockMovementResponse>> stockMovement(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(reportService.stockMovement(branchId, from, to, page, size));
    }

    @GetMapping("/stock-health")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<StockHealthResponse> stockHealth(
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "14") int targetCoverDays) {
        return ResponseEntity.ok(reportService.stockHealth(branchId, targetCoverDays));
    }

    // RPT-06: Expense Report by Category
    @GetMapping("/expenses")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<ExpenseReportSummary> expenseReport(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.expenseReport(branchId, from, to));
    }

    // RPT-07: Customer Credit Aging
    @GetMapping("/credit-aging")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<List<CreditAgingResponse>> creditAging(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(reportService.creditAging(branchId));
    }

    @GetMapping("/supplier-payables-aging")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<List<SupplierPayablesAgingResponse>> supplierPayablesAging(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(reportService.supplierPayablesAging(branchId));
    }

    @GetMapping("/customer-behavior")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<CustomerBehaviorResponse> customerBehavior(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.customerBehavior(branchId, from, to));
    }

    // RPT-08: Promotion Effectiveness
    @GetMapping("/promotions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<List<PromotionEffectivenessResponse>> promotionEffectiveness(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.promotionEffectiveness(branchId, from, to));
    }

    // RPT-09: Warranty Report
    @GetMapping("/warranties")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<WarrantyReportSummary> warrantyReport(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.warrantyReport(branchId, from, to));
    }

    // RPT-10: Stock Transfer Report
    @GetMapping("/stock-transfers")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','MANAGER')")
    public ResponseEntity<PageResponse<StockTransferReportResponse>> stockTransferReport(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long fromBranchId,
            @RequestParam(required = false) Long toBranchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                reportService.stockTransferReport(branchId, fromBranchId, toBranchId, status, from, to, page, size));
    }
}
