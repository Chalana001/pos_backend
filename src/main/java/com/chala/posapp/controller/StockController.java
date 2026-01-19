package com.chala.posapp.controller;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockResponse;
import com.chala.posapp.dto.StockUpsertRequest;
import com.chala.posapp.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    // create/update stock per branch/item
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/upsert")
    public ResponseEntity<StockResponse> upsert(@Valid @RequestBody StockUpsertRequest request) {
        return ResponseEntity.ok(stockService.upsertStock(request));
    }

    // list stock for branch
    // /stock/branch/1
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<StockResponse>> listBranchStock(@PathVariable Long branchId) {
        return ResponseEntity.ok(stockService.listBranchStock(branchId));
    }

    // get stock record for branch & item
    // /stock/branch/1/item/10
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}/item/{itemId}")
    public ResponseEntity<StockResponse> getStock(@PathVariable Long branchId, @PathVariable Long itemId) {
        return ResponseEntity.ok(stockService.getStock(branchId, itemId));
    }

    // low stock list (branch wise)
    // /stock/low?branchId=1
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low")
    public ResponseEntity<List<LowStockResponse>> lowStock(@RequestParam Long branchId) {
        return ResponseEntity.ok(stockService.lowStock(branchId));
    }
}
