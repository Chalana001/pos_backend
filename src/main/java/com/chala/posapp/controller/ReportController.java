package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.CategorySalesResponse;
import com.chala.posapp.dto.report.CreditDueResponse;
import com.chala.posapp.dto.report.CustomerPerformanceResponse;
import com.chala.posapp.dto.report.ProfitReportResponse;
import com.chala.posapp.dto.report.ProfitSummaryResponse;
import com.chala.posapp.dto.report.OwnerCommandCenterResponse;
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
import com.chala.posapp.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/owner-command-center")
    public ResponseEntity<OwnerCommandCenterResponse> ownerCommandCenter(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.ownerCommandCenter(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-summary")
    public ResponseEntity<SalesSummaryResponse> salesSummary(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.salesSummary(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/profit-summary")
    public ResponseEntity<ProfitSummaryResponse> profitSummary(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getProfitSummary(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/top-selling")
    public ResponseEntity<List<TopSellingItemResponse>> topSelling(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "itemType", required = false) String itemType,
            @RequestParam(name = "rankBy", defaultValue = "REVENUE") String rankBy,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.topSelling(branchId, from, to, limit, itemType, rankBy));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/product-performance")
    public ResponseEntity<PageResponse<TopSellingItemResponse>> productPerformance(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "itemType", required = false) String itemType,
            @RequestParam(name = "sortBy", defaultValue = "REVENUE") String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC") String sortDirection,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(reportService.productPerformance(branchId, from, to, page, size, itemType, sortBy, sortDirection));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/customer-performance")
    public ResponseEntity<PageResponse<CustomerPerformanceResponse>> customerPerformance(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "sortBy", defaultValue = "TOTAL_SPENT") String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC") String sortDirection,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(reportService.customerPerformance(branchId, from, to, page, size, sortBy, sortDirection));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/supplier-performance")
    public ResponseEntity<PageResponse<SupplierPerformanceResponse>> supplierPerformance(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "sortBy", defaultValue = "TOTAL_PURCHASED") String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC") String sortDirection,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(reportService.supplierPerformance(branchId, from, to, page, size, sortBy, sortDirection));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/sales")
    public ResponseEntity<PageResponse<SalesReportResponse>> salesReport(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "orderType", required = false) String orderType,
            @RequestParam(name = "sortBy", defaultValue = "DATE") String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC") String sortDirection,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(reportService.salesReport(branchId, from, to, page, size, orderType, sortBy, sortDirection));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "reportType") String reportType,
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "itemType", required = false) String itemType,
            @RequestParam(name = "orderType", required = false) String orderType,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC") String sortDirection) {
        byte[] bytes = reportService.exportPerformanceReport(reportType, branchId, from, to, itemType, orderType, sortBy, sortDirection);
        String filename = reportType.toLowerCase() + "-report.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/profit")
    public ResponseEntity<List<ProfitReportResponse>> profit(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "itemType", required = false) String itemType,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(reportService.profitReport(branchId, from, to, limit, itemType));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-trend")
    public ResponseEntity<List<SalesTrendPoint>> salesTrend(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "type", defaultValue = "DAILY") String type) {
        return ResponseEntity.ok(reportService.salesTrend(branchId, from, to, type));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-by-category")
    public ResponseEntity<List<CategorySalesResponse>> salesByCategory(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "itemType", required = false) String itemType) {
        return ResponseEntity.ok(reportService.salesByCategory(branchId, from, to, itemType));
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
    public ResponseEntity<List<CreditDueResponse>> creditDue(
            @RequestParam(name = "branchId", required = false) Long branchId) {
        return ResponseEntity.ok(reportService.creditDueList(branchId));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/top-customers")
    public ResponseEntity<List<TopCustomerResponse>> topCustomers(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.topCustomers(branchId, limit, from, to));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/top-suppliers")
    public ResponseEntity<List<TopSupplierResponse>> topSuppliers(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.topSuppliers(branchId, limit, from, to));
    }

    // ─── Returns Reports ──────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/returns-summary")
    public ResponseEntity<ReturnsSummaryResponse> returnsSummary(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getReturnsSummary(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/top-returned-items")
    public ResponseEntity<List<TopReturnedItemResponse>> topReturnedItems(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "type", defaultValue = "SALE") String type,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.getTopReturnedItems(branchId, from, to, limit, type));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/return-reasons")
    public ResponseEntity<List<ReturnReasonBreakdownResponse>> returnReasons(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getReturnReasonBreakdown(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/return-trend")
    public ResponseEntity<List<ReturnTrendPoint>> returnTrend(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getReturnTrend(branchId, from, to));
    }
}
