package com.chala.posapp.controller;

import com.chala.posapp.dto.order.CancelOrderRequest;
import com.chala.posapp.dto.order.CreateOrderRequest;
import com.chala.posapp.dto.order.OfflineSaleImportRequest;
import com.chala.posapp.dto.order.OfflineSaleImportResponse;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.service.InvoicePdfService;
import com.chala.posapp.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final InvoicePdfService invoicePdfService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/offline-import")
    public ResponseEntity<OfflineSaleImportResponse> importOfflineSale(
            @Valid @RequestBody OfflineSaleImportRequest request
    ) {
        return ResponseEntity.ok(orderService.importOfflineSale(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/offline-import/bulk")
    public ResponseEntity<List<OfflineSaleImportResponse>> importOfflineSales(
            @RequestBody @NotEmpty(message = "List cannot be empty")
            List<@Valid OfflineSaleImportRequest> requests
    ) {
        return ResponseEntity.ok(orderService.importOfflineSales(requests));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{invoiceNo}")
    public ResponseEntity<OrderResponse> get(@PathVariable("invoiceNo") String invoiceNo) {
        return ResponseEntity.ok(orderService.getOrder(invoiceNo));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping(value = "/{invoiceNo}/invoice.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable("invoiceNo") String invoiceNo) {
        byte[] pdfBytes = invoicePdfService.generateInvoicePdf(invoiceNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + invoiceNo + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/{invoiceNo}/cancel")
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable("invoiceNo") String invoiceNo,
            @Valid @RequestBody CancelOrderRequest request
    ) {
        return ResponseEntity.ok(orderService.cancelOrder(invoiceNo, request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> list(
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "branchId", defaultValue = "0") String branchId
    ) {
        return ResponseEntity.ok(orderService.getAllOrders(search, page, size, branchId));
    }
}
