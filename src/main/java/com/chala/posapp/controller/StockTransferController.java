package com.chala.posapp.controller;

import com.chala.posapp.dto.stock.CancelTransferRequest;
import com.chala.posapp.dto.stock.CreateStockTransferRequest;
import com.chala.posapp.dto.stock.StockTransferResponse;
import com.chala.posapp.entity.stock.StockTransferStatus;
import com.chala.posapp.service.StockTransferService;
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
    public ResponseEntity<StockTransferResponse> receiveById(@PathVariable("transferId") Long transferId) {
        return ResponseEntity.ok(transferService.receiveTransferById(transferId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{transferId}/cancel")
    public ResponseEntity<StockTransferResponse> cancelById(
            @PathVariable("transferId") Long transferId,
            @Valid @RequestBody CancelTransferRequest request) {
        return ResponseEntity.ok(transferService.cancelTransferById(transferId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/incoming/{branchId}/pending")
    public ResponseEntity<List<StockTransferResponse>> incomingPending(@PathVariable("branchId") Long branchId) {
        return ResponseEntity.ok(transferService.incomingPending(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/outgoing/{branchId}/pending")
    public ResponseEntity<List<StockTransferResponse>> outgoingPending(@PathVariable("branchId") Long branchId) {
        return ResponseEntity.ok(transferService.outgoingPending(branchId));
    }

    // ✅ වෙනස් කරපු තැන: පැටලෙන්නේ නැති වෙන්න /details/ කියන කෑල්ල ඉස්සරහට දැම්මා
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/details/{transferNo}")
    public ResponseEntity<StockTransferResponse> get(@PathVariable("transferNo") String transferNo) {
        return ResponseEntity.ok(transferService.getTransfer(transferNo));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/outgoing/{fromBranchId}")
    public ResponseEntity<List<StockTransferResponse>> outgoing(@PathVariable("fromBranchId") Long fromBranchId) {
        return ResponseEntity.ok(transferService.listOutgoing(fromBranchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/incoming/{toBranchId}")
    public ResponseEntity<List<StockTransferResponse>> incoming(@PathVariable("toBranchId") Long toBranchId) {
        return ResponseEntity.ok(transferService.listIncoming(toBranchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/outgoing")
    public ResponseEntity<Page<StockTransferResponse>> outgoingPage(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) StockTransferStatus status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(transferService.listOutgoingPage(branchId, search, status, from, to, page, size));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/incoming")
    public ResponseEntity<Page<StockTransferResponse>> incomingPage(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) StockTransferStatus status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(transferService.listIncomingPage(branchId, search, status, from, to, page, size));
    }
}
