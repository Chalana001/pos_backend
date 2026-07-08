package com.chala.posapp.controller;

import com.chala.posapp.dto.purchaseReturns.CreatePurchaseReturnRequest;
import com.chala.posapp.dto.purchaseReturns.PurchaseReturnResponse;
import com.chala.posapp.service.PurchaseReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PurchaseReturnController {

    private final PurchaseReturnService purchaseReturnService;

    /**
     * POST /purchases/{purchaseId}/returns
     * Process a partial purchase return (Debit Note).
     * Only MANAGER and ADMIN can process.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/purchases/{purchaseId}/returns")
    public ResponseEntity<PurchaseReturnResponse> processReturn(
            @PathVariable Long purchaseId,
            @Valid @RequestBody CreatePurchaseReturnRequest request
    ) {
        return ResponseEntity.ok(purchaseReturnService.processReturn(purchaseId, request));
    }

    /**
     * GET /purchases/{purchaseId}/returns
     * List all debit notes for a purchase.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/purchases/{purchaseId}/returns")
    public ResponseEntity<List<PurchaseReturnResponse>> listByPurchase(
            @PathVariable Long purchaseId
    ) {
        return ResponseEntity.ok(purchaseReturnService.listByPurchase(purchaseId));
    }

    /**
     * GET /purchase-returns/{debitNoteNo}
     * Fetch single debit note by number (for reprint).
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/purchase-returns/{debitNoteNo}")
    public ResponseEntity<PurchaseReturnResponse> getByDebitNoteNo(
            @PathVariable String debitNoteNo
    ) {
        return ResponseEntity.ok(purchaseReturnService.getByDebitNoteNo(debitNoteNo));
    }
}
