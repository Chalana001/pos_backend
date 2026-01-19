package com.chala.posapp.controller;

import com.chala.posapp.dto.CancelOrderRequest;
import com.chala.posapp.dto.CreateOrderRequest;
import com.chala.posapp.dto.OrderResponse;
import com.chala.posapp.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // CASHIER can create orders
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }

    // /orders/INV-2026-01-B1-000001
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{invoiceNo}")
    public ResponseEntity<OrderResponse> get(@PathVariable String invoiceNo) {
        return ResponseEntity.ok(orderService.getOrder(invoiceNo));
    }

    // cancel order
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/{invoiceNo}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable String invoiceNo,
                                                @Valid @RequestBody CancelOrderRequest request) {
        return ResponseEntity.ok(orderService.cancelOrder(invoiceNo, request));
    }
}
