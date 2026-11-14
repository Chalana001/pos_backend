package com.chala.posapp.controller;

import com.chala.posapp.dto.rbac.OwnershipTransferRequest;
import com.chala.posapp.dto.rbac.TenantOwnershipResponse;
import com.chala.posapp.service.TenantOwnershipService;
import com.chala.posapp.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/tenant/ownership", "/api/tenant/ownership"})
@RequiredArgsConstructor
public class TenantOwnershipController {
    private final TenantOwnershipService ownershipService;
    private final SecurityUtils securityUtils;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TenantOwnershipResponse> current() {
        return ResponseEntity.ok(TenantOwnershipResponse.from(ownershipService.current()));
    }

    @PostMapping("/transfer")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TenantOwnershipResponse> transfer(
            @Valid @RequestBody OwnershipTransferRequest request) {
        if (!ownershipService.canManageOwnership(securityUtils.getCurrentUser())) {
            throw new org.springframework.security.access.AccessDeniedException("Tenant owner authority required");
        }
        return ResponseEntity.ok()
                .header("X-Session-Refresh-Required", "true")
                .body(TenantOwnershipResponse.from(
                        ownershipService.transfer(request.getTargetUserId(), request.getExpectedVersion(), request.getReauthenticationChallenge())));
    }
}
