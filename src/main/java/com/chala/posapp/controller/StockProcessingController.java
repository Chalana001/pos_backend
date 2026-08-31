package com.chala.posapp.controller;

import com.chala.posapp.dto.item.StockProcessingOutputLinkResponse;
import com.chala.posapp.dto.stock.CancelStockProcessingRequest;
import com.chala.posapp.dto.stock.CreateStockProcessingRequest;
import com.chala.posapp.dto.stock.StockProcessingResponse;
import com.chala.posapp.dto.stock.StockProcessingSourceResponse;
import com.chala.posapp.service.StockProcessingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/stock-processing")
@RequiredArgsConstructor
public class StockProcessingController {

    private final StockProcessingService stockProcessingService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sources")
    public ResponseEntity<List<StockProcessingSourceResponse>> sources(
            @RequestParam(name = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(stockProcessingService.listSources(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sources/{sourceItemId}/outputs")
    public ResponseEntity<List<StockProcessingOutputLinkResponse>> sourceOutputs(
            @PathVariable("sourceItemId") Long sourceItemId
    ) {
        return ResponseEntity.ok(stockProcessingService.getSourceOutputs(sourceItemId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<StockProcessingResponse> create(@Valid @RequestBody CreateStockProcessingRequest request) {
        return ResponseEntity.ok(stockProcessingService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<Page<StockProcessingResponse>> history(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "sourceItemId", required = false) Long sourceItemId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(stockProcessingService.history(branchId, sourceItemId, from, to, page, size));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<StockProcessingResponse> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(stockProcessingService.get(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<StockProcessingResponse> cancel(
            @PathVariable("id") Long id,
            @Valid @RequestBody CancelStockProcessingRequest request
    ) {
        return ResponseEntity.ok(stockProcessingService.cancel(id, request));
    }
}
