package com.chala.posapp.controller;

import com.chala.posapp.dto.rbac.*;
import com.chala.posapp.rbac.PermissionCatalog;
import com.chala.posapp.rbac.RoleManagementService;
import com.chala.posapp.service.TenantOwnershipService;
import com.chala.posapp.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping({"/rbac", "/api/rbac"})
@RequiredArgsConstructor
public class RbacController {
    private final RoleManagementService roleService;
    private final TenantOwnershipService ownershipService;
    private final SecurityUtils securityUtils;

    @GetMapping("/permissions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PermissionCatalogResponse>> permissions() {
        return ResponseEntity.ok(PermissionCatalog.all().stream().map(PermissionCatalogResponse::from).toList());
    }

    @GetMapping("/roles")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RoleResponse>> roles() {
        return ResponseEntity.ok(roleService.list());
    }

    @GetMapping("/roles/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RoleResponse> role(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.get(id));
    }

    @PostMapping("/roles")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RoleResponse> updateRole(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(roleService.update(id, request));
    }

    @PatchMapping("/roles/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RoleResponse> roleStatus(@PathVariable Long id,
                                                    @RequestParam boolean active,
                                                    @RequestParam long expectedVersion) {
        return ResponseEntity.ok(roleService.setActive(id, active, expectedVersion));
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> assignRole(@PathVariable Long userId,
                                            @Valid @RequestBody AssignRoleRequest request) {
        roleService.assign(userId, request.getRoleId(), request.getExpectedVersion());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/effective-permissions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EffectivePermissionsResponse> effective(@PathVariable Long userId) {
        boolean owner = ownershipService.isOwner(userId);
        return ResponseEntity.ok(EffectivePermissionsResponse.builder()
                .userId(userId)
                .owner(owner)
                .permissionKeys(roleService.effectivePermissions(userId))
                .ownerPermissionKeys(owner ? Set.of("tenant.owner.manage", "tenant.owner.transfer", "tenant.owner.lifecycle") : Set.of())
                .rbacVersion(roleService.rbacVersion(userId))
                .build());
    }

    @GetMapping("/me/effective-permissions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EffectivePermissionsResponse> effectiveForCurrentUser() {
        var user = securityUtils.getCurrentUser();
        return effective(user.getId());
    }

    @GetMapping("/ownership")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TenantOwnershipResponse> ownership() {
        return ResponseEntity.ok(TenantOwnershipResponse.from(ownershipService.current()));
    }

    @GetMapping("/audit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(roleService.audit(limit));
    }
}
