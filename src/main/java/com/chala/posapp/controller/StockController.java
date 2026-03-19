package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
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

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<StockResponseWithItems>> listBranchStock(@PathVariable (name = "branchId") Long branchId) {
        System.out.println("braanch iddd"+branchId);
        return ResponseEntity.ok(stockService.listBranchStock(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low")
    public ResponseEntity<List<LowStockResponse>> lowStock(@RequestParam (name = "branchId") Long branchId) {
        return ResponseEntity.ok(stockService.lowStock(branchId));
    }
}
