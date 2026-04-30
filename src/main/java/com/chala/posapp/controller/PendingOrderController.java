package com.chala.posapp.controller;

import com.chala.posapp.dto.dining.PendingOrderResponse;
import com.chala.posapp.dto.dining.PendingOrderSaveRequest;
import com.chala.posapp.service.PendingOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/pending-orders")
@RequiredArgsConstructor
public class PendingOrderController {

    private final PendingOrderService pendingOrderService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PutMapping("/table/{tableId}")
    public ResponseEntity<PendingOrderResponse> saveForTable(
            @PathVariable Long tableId,
            @Valid @RequestBody PendingOrderSaveRequest request
    ) {
        return ResponseEntity.ok(pendingOrderService.saveForTable(tableId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/table/{tableId}")
    public ResponseEntity<PendingOrderResponse> getByTable(@PathVariable Long tableId) {
        return ResponseEntity.ok(pendingOrderService.getByTable(tableId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<PendingOrderResponse>> listByBranch(@RequestParam Long branchId) {
        return ResponseEntity.ok(pendingOrderService.listByBranch(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @DeleteMapping("/table/{tableId}")
    public ResponseEntity<Void> clear(@PathVariable Long tableId) {
        pendingOrderService.clearTable(tableId);
        return ResponseEntity.noContent().build();
    }
}
