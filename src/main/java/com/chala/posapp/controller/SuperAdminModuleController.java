package com.chala.posapp.controller;

import com.chala.posapp.dto.saas.module.ModuleCatalogResponse;
import com.chala.posapp.dto.saas.module.ModuleToggleRequest;
import com.chala.posapp.dto.saas.module.PlanModulesResponse;
import com.chala.posapp.dto.saas.module.TenantModulesResponse;
import com.chala.posapp.module.ModuleRouteResolver;
import com.chala.posapp.service.SuperAdminModuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Module control plane. Everything here is super-admin only and operates on the master database.
 *
 * <p>Mounted under {@code /api/saas/admin} alongside {@link SuperAdminSaasController}, matching
 * the {@code /api/...} convention the newer SaaS surfaces use.
 */
@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminModuleController {

    private final SuperAdminModuleService moduleService;
    private final ModuleRouteResolver routeResolver;

    /** The catalog of everything that can be sold, grouped by category. */
    @GetMapping("/modules")
    public ResponseEntity<ModuleCatalogResponse> catalog() {
        return ResponseEntity.ok(moduleService.getCatalog());
    }

    /**
     * Requests seen since boot that no module claims — a gap in the catalog, shown on the
     * panel's system health page so new controllers do not stay silently ungated.
     */
    @GetMapping("/modules/unmapped-routes")
    public ResponseEntity<List<String>> unmappedRoutes() {
        return ResponseEntity.ok(routeResolver.unmappedPaths());
    }

    @GetMapping("/shops/{tenantId}/modules")
    public ResponseEntity<TenantModulesResponse> shopModules(@PathVariable String tenantId) {
        return ResponseEntity.ok(moduleService.getTenantModules(tenantId));
    }

    /** Batch toggle for one shop. {@code enabled: null} on a change means "follow the plan". */
    @PatchMapping("/shops/{tenantId}/modules")
    public ResponseEntity<TenantModulesResponse> updateShopModules(
            @PathVariable String tenantId,
            @Valid @RequestBody ModuleToggleRequest request
    ) {
        return ResponseEntity.ok(moduleService.updateTenantModules(tenantId, request));
    }

    /** Drops every override so the shop follows its plan template exactly. */
    @PostMapping("/shops/{tenantId}/modules/reset")
    public ResponseEntity<TenantModulesResponse> resetShopModules(@PathVariable String tenantId) {
        return ResponseEntity.ok(moduleService.resetTenantToPlan(tenantId));
    }

    @GetMapping("/plans/{planId}/modules")
    public ResponseEntity<PlanModulesResponse> planModules(@PathVariable Long planId) {
        return ResponseEntity.ok(moduleService.getPlanModules(planId));
    }

    /** Edits the plan template. Affects every shop on the plan that has no override. */
    @PatchMapping("/plans/{planId}/modules")
    public ResponseEntity<PlanModulesResponse> updatePlanModules(
            @PathVariable Long planId,
            @Valid @RequestBody ModuleToggleRequest request
    ) {
        return ResponseEntity.ok(moduleService.updatePlanModules(planId, request));
    }
}
