package com.chala.posapp.controller;

import com.chala.posapp.dto.receipt.ReceiptSettingsRequest;
import com.chala.posapp.dto.receipt.ReceiptSettingsResponse;
import com.chala.posapp.entity.PrintTemplateType;
import com.chala.posapp.service.ReceiptSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/branches/{branchId}/receipt-settings")
@RequiredArgsConstructor
public class ReceiptSettingsController {

    private final ReceiptSettingsService receiptSettingsService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<ReceiptSettingsResponse> getSettings(
            @PathVariable Long branchId,
            @RequestParam(name = "templateType", defaultValue = "THERMAL") PrintTemplateType templateType
    ) {
        return ResponseEntity.ok(receiptSettingsService.getSettings(branchId, templateType));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping
    public ResponseEntity<ReceiptSettingsResponse> updateSettings(
            @PathVariable Long branchId,
            @RequestParam(name = "templateType", defaultValue = "THERMAL") PrintTemplateType templateType,
            @Valid @RequestBody ReceiptSettingsRequest request
    ) {
        return ResponseEntity.ok(receiptSettingsService.updateSettings(branchId, templateType, request));
    }
}
