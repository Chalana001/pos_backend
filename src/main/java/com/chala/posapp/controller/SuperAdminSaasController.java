package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.saas.*;
import com.chala.posapp.service.SuperAdminSaasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminSaasController {

    private final SuperAdminSaasService superAdminSaasService;

    @GetMapping("/dashboard")
    public ResponseEntity<SuperAdminDashboardResponse> dashboard() {
        return ResponseEntity.ok(superAdminSaasService.getDashboard());
    }

    @GetMapping("/shops")
    public ResponseEntity<PageResponse<ShopSummaryResponse>> shops(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "all") String status
    ) {
        return ResponseEntity.ok(superAdminSaasService.getAllShops(page, size, search, status));
    }

    @PostMapping("/shops")
    public ResponseEntity<ShopSummaryResponse> onboard(@Valid @RequestBody ShopOnboardRequest request) {
        return ResponseEntity.ok(superAdminSaasService.onboardShop(request));
    }

    @PatchMapping("/shops/{tenantId}/block")
    public ResponseEntity<ShopSummaryResponse> blockOrUnblock(
            @PathVariable String tenantId,
            @Valid @RequestBody ShopBlockRequest request
    ) {
        return ResponseEntity.ok(superAdminSaasService.updateShopBlockStatus(tenantId, request));
    }

    @PatchMapping("/shops/{tenantId}/admin-password")
    public ResponseEntity<ShopSummaryResponse> resetAdminPassword(
            @PathVariable String tenantId,
            @Valid @RequestBody ResetShopAdminPasswordRequest request
    ) {
        return ResponseEntity.ok(superAdminSaasService.resetAdminPassword(tenantId, request));
    }

    @GetMapping("/shops/{tenantId}")
    public ResponseEntity<ShopDetailsResponse> shopDetails(@PathVariable String tenantId) {
        return ResponseEntity.ok(superAdminSaasService.getShopDetails(tenantId));
    }

    @GetMapping("/shops/{tenantId}/payments")
    public ResponseEntity<java.util.List<ShopPaymentResponse>> payments(@PathVariable String tenantId) {
        return ResponseEntity.ok(superAdminSaasService.getShopPayments(tenantId));
    }

    @PostMapping("/shops/{tenantId}/renew")
    public ResponseEntity<ShopSummaryResponse> renew(
            @PathVariable String tenantId,
            @Valid @RequestBody ManualRenewRequest request
    ) {
        return ResponseEntity.ok(superAdminSaasService.renewSubscription(tenantId, request));
    }

    @PostMapping("/shops/{tenantId}/package")
    public ResponseEntity<ShopSummaryResponse> changePackage(
            @PathVariable String tenantId,
            @Valid @RequestBody ChangePackageRequest request
    ) {
        return ResponseEntity.ok(superAdminSaasService.changePackage(tenantId, request));
    }

    @PostMapping("/shops/{tenantId}/extra-branches")
    public ResponseEntity<ShopSummaryResponse> addExtraBranches(
            @PathVariable String tenantId,
            @Valid @RequestBody AddExtraBranchesRequest request
    ) {
        return ResponseEntity.ok(superAdminSaasService.addExtraBranches(tenantId, request));
    }
}
