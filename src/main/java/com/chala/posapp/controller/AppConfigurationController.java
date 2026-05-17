package com.chala.posapp.controller;

import com.chala.posapp.dto.configuration.AppConfigurationRequest;
import com.chala.posapp.dto.configuration.AppConfigurationResponse;
import com.chala.posapp.service.AppConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app-configuration")
@RequiredArgsConstructor
public class AppConfigurationController {

    private final AppConfigurationService appConfigurationService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<AppConfigurationResponse> getConfiguration() {
        return ResponseEntity.ok(appConfigurationService.getConfiguration());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<AppConfigurationResponse> updateConfiguration(@RequestBody AppConfigurationRequest request) {
        return ResponseEntity.ok(appConfigurationService.updateConfiguration(request));
    }
}
