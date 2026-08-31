package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.report.ReportExportJobRequest;
import com.chala.posapp.dto.report.ReportExportJobResponse;
import com.chala.posapp.entity.ReportExportJob;
import com.chala.posapp.service.ReportExportJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reports/export-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
public class ReportExportJobController {
    private final ReportExportJobService service;

    @PostMapping
    public ResponseEntity<ReportExportJobResponse> create(@RequestBody ReportExportJobRequest request) {
        return ResponseEntity.accepted().body(service.create(request));
    }

    @GetMapping
    public ResponseEntity<PageResponse<ReportExportJobResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReportExportJobResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.toResponse(service.getOwnedJob(id)));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        ReportExportJob job = service.getOwnedJob(id);
        byte[] bytes = service.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(job.getFileName()).build().toString())
                .contentType(MediaType.parseMediaType(ReportExportJobService.XLSX_CONTENT_TYPE))
                .body(bytes);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReportExportJobResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(service.cancel(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ReportExportJobResponse> retry(@PathVariable Long id) {
        return ResponseEntity.ok(service.retry(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
