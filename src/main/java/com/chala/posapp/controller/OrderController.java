package com.chala.posapp.controller;

import com.chala.posapp.dto.order.CancelOrderRequest;
import com.chala.posapp.dto.order.CreateOrderRequest;
import com.chala.posapp.dto.order.OrderResponse;
import com.chala.posapp.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page; // 🔴 Page import එක
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders") // 🔴 මේක /orders නිසා Frontend API එකත් අපි වෙනස් කරමු
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // Cashier create orders (POS එකෙන් බිල් ගහද්දි)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }

    // Get order by Invoice No (බිල් එකක් වෙනම බලන්න)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{invoiceNo}")
    public ResponseEntity<OrderResponse> get(@PathVariable("invoiceNo") String invoiceNo) {
        return ResponseEntity.ok(orderService.getOrder(invoiceNo));
    }

    // Cancel order
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/{invoiceNo}/cancel")
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable("invoiceNo") String invoiceNo,
            @Valid @RequestBody CancelOrderRequest request
    ) {
        return ResponseEntity.ok(orderService.cancelOrder(invoiceNo, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
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