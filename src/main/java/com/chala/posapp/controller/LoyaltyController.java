package com.chala.posapp.controller;

import com.chala.posapp.dto.loyalty.LoyaltyAccountDto;
import com.chala.posapp.dto.loyalty.LoyaltyAdjustRequest;
import com.chala.posapp.dto.loyalty.LoyaltySettingsDto;
import com.chala.posapp.dto.loyalty.LoyaltyTierDto;
import com.chala.posapp.dto.loyalty.LoyaltyTransactionDto;
import com.chala.posapp.service.LoyaltyService;
import com.chala.posapp.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Loyalty points. Root-mounted like the other shop-facing controllers, and its own module —
 * a shop can buy promotions without buying this.
 */
@RestController
@RequestMapping("/loyalty")
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyService loyaltyService;
    private final SecurityUtils securityUtils;

    /** The cashier needs this at the till to offer a redemption, so it is not admin-only. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/customers/{customerId}")
    public ResponseEntity<LoyaltyAccountDto> account(@PathVariable Long customerId) {
        return ResponseEntity.ok(loyaltyService.account(customerId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/customers/{customerId}/history")
    public ResponseEntity<List<LoyaltyTransactionDto>> history(@PathVariable Long customerId) {
        return ResponseEntity.ok(loyaltyService.history(customerId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/customers/{customerId}/adjust")
    public ResponseEntity<LoyaltyAccountDto> adjust(
            @PathVariable Long customerId,
            @Valid @RequestBody LoyaltyAdjustRequest request
    ) {
        return ResponseEntity.ok(loyaltyService.adjust(
                customerId, request.getPoints(), request.getNote(), securityUtils.getCurrentUser()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/settings")
    public ResponseEntity<LoyaltySettingsDto> settings() {
        return ResponseEntity.ok(loyaltyService.settingsDto());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/settings")
    public ResponseEntity<LoyaltySettingsDto> updateSettings(@Valid @RequestBody LoyaltySettingsDto request) {
        return ResponseEntity.ok(loyaltyService.updateSettings(request, securityUtils.getCurrentUser()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/tiers")
    public ResponseEntity<List<LoyaltyTierDto>> tiers() {
        return ResponseEntity.ok(loyaltyService.tiers());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/tiers")
    public ResponseEntity<LoyaltyTierDto> createTier(@Valid @RequestBody LoyaltyTierDto request) {
        return ResponseEntity.ok(loyaltyService.saveTier(null, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/tiers/{id}")
    public ResponseEntity<LoyaltyTierDto> updateTier(@PathVariable Long id, @Valid @RequestBody LoyaltyTierDto request) {
        return ResponseEntity.ok(loyaltyService.saveTier(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/tiers/{id}")
    public ResponseEntity<Void> deleteTier(@PathVariable Long id) {
        loyaltyService.deleteTier(id);
        return ResponseEntity.noContent().build();
    }
}
