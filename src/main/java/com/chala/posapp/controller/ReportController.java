package com.chala.posapp.controller;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.report.*;
import com.chala.posapp.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-summary")
    public ResponseEntity<SalesSummaryResponse> salesSummary(
            @RequestParam(required = false) Long branchId,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        return ResponseEntity.ok(
                reportService.salesSummary(branchId, from, to)
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/top-selling")
    public ResponseEntity<List<TopSellingItemResponse>> topSelling(
            @RequestParam(required = false) Long branchId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(
                reportService.topSelling(branchId, from, to, limit)
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/profit")
    public ResponseEntity<List<ProfitReportResponse>> profit(
            @RequestParam(required = false) Long branchId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(
                reportService.profitReport(branchId, from, to, limit)
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low-stock")
    public ResponseEntity<List<LowStockResponse>> lowStock(
            @RequestParam(required = false) Long branchId
    ) {
        return ResponseEntity.ok(reportService.lowStock(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/credit-due")
    public ResponseEntity<List<CreditDueResponse>> creditDue() {
        return ResponseEntity.ok(reportService.creditDueList());
    }

    @GetMapping("/sales-trend")
    public ResponseEntity<List<SalesTrendPoint>> salesTrend(
            @RequestParam Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        return ResponseEntity.ok(reportService.salesTrend(branchId, from, to));
    }

}
