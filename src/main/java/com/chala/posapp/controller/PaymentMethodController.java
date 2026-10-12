package com.chala.posapp.controller;

import com.chala.posapp.dto.paymentmethod.PaymentMethodRequest;
import com.chala.posapp.dto.paymentmethod.PaymentMethodResponse;
import com.chala.posapp.service.PaymentMethodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/payment-methods", "/api/payment-methods"})
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    /** Active methods for checkout; any signed-in staff role may read these. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER','CASHIER')")
    @GetMapping("/active")
    public ResponseEntity<List<PaymentMethodResponse>> listActive() {
        return ResponseEntity.ok(paymentMethodService.listActive());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<PaymentMethodResponse>> listAll() {
        return ResponseEntity.ok(paymentMethodService.listAll());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<PaymentMethodResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentMethodService.getById(id));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<PaymentMethodResponse> create(@Valid @RequestBody PaymentMethodRequest request) {
        return ResponseEntity.ok(paymentMethodService.create(request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<PaymentMethodResponse> update(@PathVariable Long id,
            @Valid @RequestBody PaymentMethodRequest request) {
        return ResponseEntity.ok(paymentMethodService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PatchMapping("/{id}/active")
    public ResponseEntity<PaymentMethodResponse> setActive(@PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(paymentMethodService.setActive(id, Boolean.TRUE.equals(body.get("active"))));
    }
}
