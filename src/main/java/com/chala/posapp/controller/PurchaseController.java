package com.chala.posapp.controller;

import com.chala.posapp.dto.CreatePurchaseRequest;
import com.chala.posapp.dto.PurchaseResponse;
import com.chala.posapp.dto.CancelPurchaseRequest;
import com.chala.posapp.dto.purchase.PurchaseImportLookupRequest;
import com.chala.posapp.dto.purchase.PurchaseImportLookupResponse;
import com.chala.posapp.dto.purchase.PurchaseImportPreviewResponse;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.service.PurchaseExcelImportService;
import com.chala.posapp.service.PurchaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@RestController
@RequestMapping("/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final PurchaseExcelImportService purchaseExcelImportService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<PurchaseResponse> create(@RequestBody CreatePurchaseRequest request) {
        return ResponseEntity.ok(purchaseService.createPurchase(request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<Page<PurchaseResponse>> getAll(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "supplierId", required = false) Long supplierId,
            @RequestParam(name = "status", required = false) PurchaseStatus status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(purchaseService.getAllPurchases(search, supplierId, status, from, to, page, size));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<PurchaseResponse> getById(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(purchaseService.getPurchaseById(id));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<PurchaseResponse> cancel(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody CancelPurchaseRequest request
    ) {
        return ResponseEntity.ok(purchaseService.cancelPurchase(id, request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] bytes = purchaseExcelImportService.generateTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "purchase-import-template.xlsx");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PurchaseImportPreviewResponse> previewImport(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(purchaseExcelImportService.previewFromFile(file));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping("/import/lookup")
    public ResponseEntity<PurchaseImportLookupResponse> lookupImportRow(@RequestBody PurchaseImportLookupRequest request) {
        return ResponseEntity.ok(purchaseExcelImportService.lookupSingleRow(request));
    }
}
