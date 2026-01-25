package com.chala.posapp.controller;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockResponseWithItems;
import com.chala.posapp.service.StockService;
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

    // list stock for branch
    // /stock/branch/1
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<StockResponseWithItems>> listBranchStock(@PathVariable Long branchId) {
        System.out.println("braanch iddd"+branchId);
        return ResponseEntity.ok(stockService.listBranchStock(branchId));
    }

    // low stock list (branch wise)
    // /stock/low?branchId=1
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low")
    public ResponseEntity<List<LowStockResponse>> lowStock(@RequestParam Long branchId) {
        return ResponseEntity.ok(stockService.lowStock(branchId));
    }
}
