package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
    public ResponseEntity<Page<StockResponseWithItems>> listBranchStock(
            @PathVariable(name = "branchId") Long branchId,
            @RequestParam(name = "search", required = false, defaultValue = "") String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(stockService.listBranchStock(branchId, search, page, size));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/low")
    public ResponseEntity<List<LowStockResponse>> lowStock(@RequestParam (name = "branchId") Long branchId) {
        return ResponseEntity.ok(stockService.lowStock(branchId));
    }
}
