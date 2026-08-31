package com.chala.posapp.controller;

import com.chala.posapp.dto.returns.CreateReturnRequest;
import com.chala.posapp.dto.returns.OrderReturnResponse;
import com.chala.posapp.service.OrderReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class OrderReturnController {

    private final OrderReturnService orderReturnService;

    /**
     * POST /orders/{invoiceNo}/returns
     * Process a partial return for a completed order.
     * Only MANAGER and ADMIN can process returns.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/orders/{invoiceNo}/returns")
    public ResponseEntity<OrderReturnResponse> processReturn(
            @PathVariable("invoiceNo") String invoiceNo,
            @Valid @RequestBody CreateReturnRequest request
    ) {
        return ResponseEntity.ok(orderReturnService.processReturn(invoiceNo, request));
    }

    /**
     * GET /orders/{invoiceNo}/returns
     * List all returns for a specific invoice.
     * CASHIER can view (read-only), MANAGER and ADMIN can also process.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/orders/{invoiceNo}/returns")
    public ResponseEntity<List<OrderReturnResponse>> listByInvoice(
            @PathVariable("invoiceNo") String invoiceNo
    ) {
        return ResponseEntity.ok(orderReturnService.listByInvoice(invoiceNo));
    }

    /**
     * GET /returns/{returnNo}
     * Fetch a single return by its return number (for receipt reprint).
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/returns/{returnNo}")
    public ResponseEntity<OrderReturnResponse> getByReturnNo(
            @PathVariable("returnNo") String returnNo
    ) {
        return ResponseEntity.ok(orderReturnService.getByReturnNo(returnNo));
    }
}
