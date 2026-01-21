package com.chala.posapp.controller;

import com.chala.posapp.dto.dashboard.DashboardKpiResponse;
import com.chala.posapp.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


import com.chala.posapp.dto.chart.DailySalesResponse;
import com.chala.posapp.dto.chart.MonthlySalesResponse;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.util.List;



@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/kpis")
    public ResponseEntity<DashboardKpiResponse> todayKpis(@RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(dashboardService.todayKpis(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/charts/daily")
    public ResponseEntity<List<DailySalesResponse>> dailySales(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(dashboardService.dailySales(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/charts/monthly")
    public ResponseEntity<List<MonthlySalesResponse>> monthlySales(
            @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(dashboardService.monthlySales(branchId, from, to));
    }

}
