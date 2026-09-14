package com.chala.posapp.controller;

import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsRequest;
import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsResponse;
import com.chala.posapp.service.BarcodeLabelSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/branches/{branchId}/barcode-label-settings")
@RequiredArgsConstructor
public class BarcodeLabelSettingsController {

    private final BarcodeLabelSettingsService barcodeLabelSettingsService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<BarcodeLabelSettingsResponse> getSettings(@PathVariable Long branchId) {
        return ResponseEntity.ok(barcodeLabelSettingsService.getSettings(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping
    public ResponseEntity<BarcodeLabelSettingsResponse> updateSettings(
            @PathVariable Long branchId,
            @Valid @RequestBody BarcodeLabelSettingsRequest request) {
        return ResponseEntity.ok(barcodeLabelSettingsService.updateSettings(branchId, request));
    }
}
