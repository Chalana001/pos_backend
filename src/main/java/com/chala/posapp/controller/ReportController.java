package com.chala.posapp.controller;

import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.report.RecentOrderResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-summary")
    public ResponseEntity<SalesSummaryResponse> salesSummary(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to) {
        return ResponseEntity.ok(reportService.salesSummary(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/profit-summary")
    public ResponseEntity<ProfitSummaryResponse> profitSummary(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to) {
        return ResponseEntity.ok(reportService.getProfitSummary(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/top-selling")
    public ResponseEntity<List<TopSellingItemResponse>> topSelling(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.topSelling(branchId, from, to, limit));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/profit")
    public ResponseEntity<List<ProfitReportResponse>> profit(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(reportService.profitReport(branchId, from, to, limit));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-trend")
    public ResponseEntity<List<SalesTrendPoint>> salesTrend(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to,
            @RequestParam(name = "type", defaultValue = "DAILY") String type) { // 🔥 New Parameter

        return ResponseEntity.ok(reportService.salesTrend(branchId, from, to, type));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-by-category")
    public ResponseEntity<List<CategorySalesResponse>> salesByCategory(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to) {
        return ResponseEntity.ok(reportService.salesByCategory(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/recent-orders")
    public ResponseEntity<List<RecentOrderResponse>> recentOrders(
            @RequestParam(name = "branchId", required = false) Long branchId) {
        return ResponseEntity.ok(reportService.recentOrders(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low-stock")
    public ResponseEntity<List<LowStockResponse>> lowStock(
            @RequestParam(name = "branchId", required = false) Long branchId) {
        return ResponseEntity.ok(reportService.lowStock(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/credit-due")
    public ResponseEntity<List<CreditDueResponse>> creditDue() {
        return ResponseEntity.ok(reportService.creditDueList());
    }

    @GetMapping("/top-customers")
    public ResponseEntity<List<TopCustomerResponse>> topCustomers(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.topCustomers(branchId, limit));
    }

    @GetMapping("/top-suppliers")
    public ResponseEntity<List<TopSupplierResponse>> topSuppliers(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.topSuppliers(branchId, limit));
    }
}