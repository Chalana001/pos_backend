package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.CreateStockAdjustmentRequest;
import com.chala.posapp.dto.stock.StockAdjustmentResponse;
import com.chala.posapp.entity.stock.StockAdjustmentType;
import com.chala.posapp.service.StockAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    public ResponseEntity<List<StockAdjustmentResponse>> historyBranch(@PathVariable (name = "branchId") Long branchId) {
        return ResponseEntity.ok(adjustmentService.historyByBranch(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}/item/{itemId}")
    public ResponseEntity<List<StockAdjustmentResponse>> historyItem(@PathVariable (name = "branchId") Long branchId,
                                                                     @PathVariable (name = "itemId") Long itemId) {
        return ResponseEntity.ok(adjustmentService.historyByItem(branchId, itemId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<Page<StockAdjustmentResponse>> history(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "type", required = false) StockAdjustmentType type,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(adjustmentService.historyPage(branchId, search, type, userId, from, to, page, size));
    }
}
