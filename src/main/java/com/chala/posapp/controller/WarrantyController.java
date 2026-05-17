package com.chala.posapp.controller;

import com.chala.posapp.dto.warranty.WarrantyClaimRequest;
import com.chala.posapp.dto.warranty.WarrantyClaimListResponse;
import com.chala.posapp.dto.warranty.WarrantyClaimResponse;
import com.chala.posapp.dto.warranty.WarrantyClaimUpdateRequest;
import com.chala.posapp.dto.warranty.WarrantyResponse;
import com.chala.posapp.entity.WarrantyClaimStatus;
import com.chala.posapp.service.WarrantyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warranties")
@RequiredArgsConstructor
public class WarrantyController {

    private final WarrantyService warrantyService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<Page<WarrantyResponse>> list(
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "branchId", defaultValue = "0") String branchId
    ) {
        return ResponseEntity.ok(warrantyService.list(search, page, size, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/claims")
    public ResponseEntity<Page<WarrantyClaimListResponse>> listClaimQueue(
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "status", required = false) WarrantyClaimStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "branchId", defaultValue = "0") String branchId
    ) {
        return ResponseEntity.ok(warrantyService.listClaimQueue(search, status, page, size, branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<WarrantyResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(warrantyService.get(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}/claims")
    public ResponseEntity<java.util.List<WarrantyClaimResponse>> listClaims(@PathVariable Long id) {
        return ResponseEntity.ok(warrantyService.listClaims(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/{id}/claims")
    public ResponseEntity<WarrantyClaimResponse> createClaim(
            @PathVariable Long id,
            @Valid @RequestBody WarrantyClaimRequest request
    ) {
        return ResponseEntity.ok(warrantyService.createClaim(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PutMapping("/{id}/claims/{claimId}")
    public ResponseEntity<WarrantyClaimResponse> updateClaim(
            @PathVariable Long id,
            @PathVariable Long claimId,
            @Valid @RequestBody WarrantyClaimUpdateRequest request
    ) {
        return ResponseEntity.ok(warrantyService.updateClaim(id, claimId, request));
    }
}
