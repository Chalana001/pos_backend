package com.chala.posapp.controller;

import com.chala.posapp.barcode.ScaleBarcodeFormatPresets;
import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsRequest;
import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsResponse;
import com.chala.posapp.dto.barcodelabel.ScaleBarcodePresetResponse;
import com.chala.posapp.service.BarcodeLabelSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // Static starting-point templates for the scale-barcode format fields — see
    // ScaleBarcodeFormatPresets. branchId is unused (same list for every branch)
    // but kept in the path so this route stays under the module-covered prefix
    // "/branches/*/barcode-label-settings/**" in ModuleCatalog.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/scale-presets")
    public ResponseEntity<List<ScaleBarcodePresetResponse>> getScaleBarcodePresets(@PathVariable Long branchId) {
        return ResponseEntity.ok(ScaleBarcodeFormatPresets.ALL);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping
    public ResponseEntity<BarcodeLabelSettingsResponse> updateSettings(
            @PathVariable Long branchId,
            @Valid @RequestBody BarcodeLabelSettingsRequest request) {
        return ResponseEntity.ok(barcodeLabelSettingsService.updateSettings(branchId, request));
    }
}
