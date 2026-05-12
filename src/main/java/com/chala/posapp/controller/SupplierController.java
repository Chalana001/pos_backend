package com.chala.posapp.controller;

import com.chala.posapp.dto.supplier.SupplierCreateRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentResponse;
import com.chala.posapp.dto.supplier.SupplierQuickCreateRequest;
import com.chala.posapp.dto.supplier.SupplierResponse;
import com.chala.posapp.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody SupplierCreateRequest req) {
        return ResponseEntity.ok(supplierService.create(req));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping("/quick")
    public ResponseEntity<?> createQuick(@RequestBody SupplierQuickCreateRequest req) {
        return ResponseEntity.ok(supplierService.createQuickSupplier(req));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<SupplierResponse>> getAll() {
        return ResponseEntity.ok(supplierService.getAllActiveSuppliers());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponse> getById(@PathVariable (name = "id") Long id) {
        return ResponseEntity.ok(supplierService.getSupplierById(id));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping("/{id}/payments")
    public ResponseEntity<SupplierResponse> recordPayment(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody SupplierPaymentRequest request
    ) {
        return ResponseEntity.ok(supplierService.recordPayment(id, request));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<SupplierPaymentResponse>> paymentHistory(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(supplierService.paymentHistory(id));
    }

}
