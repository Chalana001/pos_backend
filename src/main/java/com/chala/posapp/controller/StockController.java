package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockItemDetailsResponse;
import com.chala.posapp.dto.stock.StockPurchaseHistoryResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.dto.stock.StockValueResponse;
import com.chala.posapp.entity.PurchaseStatus;
import com.chala.posapp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
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
            @RequestParam(name = "stockStatus", required = false, defaultValue = "ALL") String stockStatus,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "subCategoryId", required = false) Long subCategoryId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(stockService.listBranchStock(
                branchId,
                search,
                stockStatus,
                categoryId,
                subCategoryId,
                page,
                size
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/branch/{branchId}/value")
    public ResponseEntity<StockValueResponse> getStockValue(
            @PathVariable(name = "branchId") Long branchId,
            @RequestParam(name = "search", required = false, defaultValue = "") String search,
            @RequestParam(name = "stockStatus", required = false, defaultValue = "ALL") String stockStatus,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "subCategoryId", required = false) Long subCategoryId
    ) {
        return ResponseEntity.ok(stockService.getStockValue(
                branchId,
                search,
                stockStatus,
                categoryId,
                subCategoryId
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/branch/{branchId}/item/{itemId}")
    public ResponseEntity<StockItemDetailsResponse> getItemDetails(
            @PathVariable(name = "branchId") Long branchId,
            @PathVariable(name = "itemId") Long itemId
    ) {
        return ResponseEntity.ok(stockService.getItemDetails(branchId, itemId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/branch/{branchId}/item/{itemId}/purchases")
    public ResponseEntity<Page<StockPurchaseHistoryResponse>> getItemPurchaseHistory(
            @PathVariable(name = "branchId") Long branchId,
            @PathVariable(name = "itemId") Long itemId,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "supplierId", required = false) Long supplierId,
            @RequestParam(name = "status", required = false) PurchaseStatus status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(stockService.getItemPurchaseHistory(
                branchId,
                itemId,
                search,
                supplierId,
                status,
                from,
                to,
                page,
                size
        ));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/low")
    public ResponseEntity<List<LowStockResponse>> lowStock(@RequestParam (name = "branchId") Long branchId) {
        return ResponseEntity.ok(stockService.lowStock(branchId));
    }
}
