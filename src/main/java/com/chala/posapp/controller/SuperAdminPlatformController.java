package com.chala.posapp.controller;

import com.chala.posapp.dto.PageResponse;
import com.chala.posapp.dto.saas.*;
import com.chala.posapp.service.SuperAdminAuditQueryService;
import com.chala.posapp.service.SuperAdminBillingService;
import com.chala.posapp.service.SuperAdminHealthService;
import com.chala.posapp.service.SuperAdminPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Plans, billing, estate health and the audit trail.
 *
 * <p>Grouped into one controller because they are all read-mostly platform surfaces for the same
 * audience; the shop lifecycle lives in {@link SuperAdminSaasController} and module control in
 * {@link SuperAdminModuleController}.
 */
@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminPlatformController {

    private final SuperAdminPlanService planService;
    private final SuperAdminBillingService billingService;
    private final SuperAdminHealthService healthService;
    private final SuperAdminAuditQueryService auditQueryService;

    // ------------------------------------------------------------------ plans

    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> plans(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return ResponseEntity.ok(planService.getAllPlans(includeInactive));
    }

    @GetMapping("/plans/{planId}")
    public ResponseEntity<PlanResponse> plan(@PathVariable Long planId) {
        return ResponseEntity.ok(planService.getPlan(planId));
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanResponse> createPlan(@Valid @RequestBody PlanRequest request) {
        return ResponseEntity.ok(planService.createPlan(request));
    }

    @PutMapping("/plans/{planId}")
    public ResponseEntity<PlanResponse> updatePlan(@PathVariable Long planId,
                                                   @Valid @RequestBody PlanRequest request) {
        return ResponseEntity.ok(planService.updatePlan(planId, request));
    }

    @DeleteMapping("/plans/{planId}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long planId) {
        planService.deletePlan(planId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ billing

    @GetMapping("/billing/overview")
    public ResponseEntity<RevenueOverviewResponse> revenueOverview() {
        return ResponseEntity.ok(billingService.getOverview());
    }

    @GetMapping("/billing/ledger")
    public ResponseEntity<PageResponse<BillingEntryResponse>> ledger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "all") String actionType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ResponseEntity.ok(billingService.getLedger(page, size, search, actionType, from, to));
    }

    // ------------------------------------------------------------------ health

    @GetMapping("/health/system")
    public ResponseEntity<SystemHealthResponse> systemHealth() {
        return ResponseEntity.ok(healthService.getSystemHealth());
    }

    @GetMapping("/health/shops")
    public ResponseEntity<List<TenantHealthResponse>> shopHealth() {
        return ResponseEntity.ok(healthService.getAllTenantHealth());
    }

    @GetMapping("/health/shops/{tenantId}")
    public ResponseEntity<TenantHealthResponse> shopHealth(@PathVariable String tenantId) {
        return ResponseEntity.ok(healthService.getTenantHealth(tenantId));
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/audit")
    public ResponseEntity<PageResponse<AuditEntryResponse>> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "all") String action,
            @RequestParam(required = false, defaultValue = "all") String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return ResponseEntity.ok(
                auditQueryService.search(page, size, search, action, targetType, targetId, from, to));
    }

    @GetMapping("/audit/recent")
    public ResponseEntity<List<AuditEntryResponse>> recentAudit() {
        return ResponseEntity.ok(auditQueryService.recent());
    }

    @GetMapping("/shops/{tenantId}/audit")
    public ResponseEntity<List<AuditEntryResponse>> shopAudit(@PathVariable String tenantId) {
        return ResponseEntity.ok(auditQueryService.forShop(tenantId));
    }
}
