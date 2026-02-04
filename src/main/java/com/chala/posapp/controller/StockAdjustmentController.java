package com.chala.posapp.controller;

import com.chala.posapp.dto.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.StockAdjustmentResponse;
import com.chala.posapp.service.StockAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stock-adjustments")
@RequiredArgsConstructor
public class StockAdjustmentController {

    private final StockAdjustmentService adjustmentService;

    // create adjustment (ADMIN/MANAGER)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<StockAdjustmentResponse> create(@Valid @RequestBody CreateStockAdjustmentRequest request) {
        return ResponseEntity.ok(adjustmentService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<StockAdjustmentResponse>> historyBranch(@PathVariable Long branchId) {
        return ResponseEntity.ok(adjustmentService.historyByBranch(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}/item/{itemId}")
    public ResponseEntity<List<StockAdjustmentResponse>> historyItem(@PathVariable Long branchId,
                                                                     @PathVariable Long itemId) {
        return ResponseEntity.ok(adjustmentService.historyByItem(branchId, itemId));
    }
}
