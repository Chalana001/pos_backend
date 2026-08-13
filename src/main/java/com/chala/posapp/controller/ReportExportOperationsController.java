package com.chala.posapp.controller;

import com.chala.posapp.dto.report.ReportExportOperationsResponse;
import com.chala.posapp.service.ReportExportOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operations/report-exports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ReportExportOperationsController {
    private final ReportExportOperationsService service;

    @GetMapping
    public ResponseEntity<ReportExportOperationsResponse> summary() {
        return ResponseEntity.ok(service.summary());
    }
}
