package com.chala.posapp.controller;

import com.chala.posapp.barcode.ScaleBarcodeFormatPresets;
import com.chala.posapp.dto.barcodelabel.ScaleBarcodePresetResponse;
import com.chala.posapp.dto.configuration.AppConfigurationRequest;
import com.chala.posapp.dto.configuration.AppConfigurationResponse;
import com.chala.posapp.service.AppConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/app-configuration")
@RequiredArgsConstructor
public class AppConfigurationController {

    private final AppConfigurationService appConfigurationService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<AppConfigurationResponse> getConfiguration(
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(appConfigurationService.getConfiguration(branchId));
    }

    // Static starting-point templates for the scale barcode fields of App
    // Configuration. See ScaleBarcodeFormatPresets. Same list for every branch;
    // mounted here so it stays under the SETTINGS module's
    // "/app-configuration/**" claim in ModuleCatalog.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/scale-barcode-presets")
    public ResponseEntity<List<ScaleBarcodePresetResponse>> getScaleBarcodePresets() {
        return ResponseEntity.ok(ScaleBarcodeFormatPresets.ALL);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping
    public ResponseEntity<AppConfigurationResponse> updateConfiguration(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @Valid @RequestBody AppConfigurationRequest request
    ) {
        return ResponseEntity.ok(appConfigurationService.updateConfiguration(branchId, request));
    }
}
