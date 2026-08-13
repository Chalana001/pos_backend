package com.chala.posapp.controller;

import com.chala.posapp.dto.report.ReportScheduleRequest;
import com.chala.posapp.dto.report.ReportScheduleResponse;
import com.chala.posapp.service.ReportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/reports/schedules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
public class ReportScheduleController {
    private final ReportScheduleService service;

    @PostMapping
    public ResponseEntity<ReportScheduleResponse> create(@RequestBody ReportScheduleRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping
    public ResponseEntity<List<ReportScheduleResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ReportScheduleResponse> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
