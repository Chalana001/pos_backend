package com.chala.posapp.controller;

import com.chala.posapp.dto.warranty.WarrantyTemplateRequest;
import com.chala.posapp.dto.warranty.WarrantyTemplateResponse;
import com.chala.posapp.service.WarrantyTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/warranty-templates")
@RequiredArgsConstructor
public class WarrantyTemplateController {

    private final WarrantyTemplateService warrantyTemplateService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/active")
    public ResponseEntity<List<WarrantyTemplateResponse>> listActive() {
        return ResponseEntity.ok(warrantyTemplateService.listActive());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<WarrantyTemplateResponse>> listAll() {
        return ResponseEntity.ok(warrantyTemplateService.listAll());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<WarrantyTemplateResponse> create(@Valid @RequestBody WarrantyTemplateRequest request) {
        return ResponseEntity.ok(warrantyTemplateService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<WarrantyTemplateResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody WarrantyTemplateRequest request
    ) {
        return ResponseEntity.ok(warrantyTemplateService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        warrantyTemplateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
