package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.CancelTransferRequest;
import com.chala.posapp.dto.stock.CreateStockTransferRequest;
import com.chala.posapp.dto.stock.StockTransferResponse;
import com.chala.posapp.service.StockTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stock-transfers")
@RequiredArgsConstructor
public class StockTransferController {

    private final StockTransferService transferService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<StockTransferResponse> create(@Valid @RequestBody CreateStockTransferRequest request) {
        return ResponseEntity.ok(transferService.createTransfer(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{transferId}/receive")
    public ResponseEntity<StockTransferResponse> receiveById(@PathVariable Long transferId) {
        return ResponseEntity.ok(transferService.receiveTransferById(transferId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{transferId}/cancel")
    public ResponseEntity<StockTransferResponse> cancelById(@PathVariable Long transferId,
                                                            @Valid @RequestBody CancelTransferRequest request) {
        return ResponseEntity.ok(transferService.cancelTransferById(transferId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/incoming/{branchId}/pending")
    public ResponseEntity<List<StockTransferResponse>> incomingPending(@PathVariable Long branchId) {
        return ResponseEntity.ok(transferService.incomingPending(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/outgoing/{branchId}/pending")
    public ResponseEntity<List<StockTransferResponse>> outgoingPending(@PathVariable Long branchId) {
        return ResponseEntity.ok(transferService.outgoingPending(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{transferNo}")
    public ResponseEntity<StockTransferResponse> get(@PathVariable String transferNo) {
        return ResponseEntity.ok(transferService.getTransfer(transferNo));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/outgoing/{fromBranchId}")
    public ResponseEntity<List<StockTransferResponse>> outgoing(@PathVariable Long fromBranchId) {
        return ResponseEntity.ok(transferService.listOutgoing(fromBranchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/incoming/{toBranchId}")
    public ResponseEntity<List<StockTransferResponse>> incoming(@PathVariable Long toBranchId) {
        return ResponseEntity.ok(transferService.listIncoming(toBranchId));
    }
}
