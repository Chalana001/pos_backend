package com.chala.posapp.controller;

import com.chala.posapp.dto.supplier.SupplierCreateRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentRequest;
import com.chala.posapp.dto.supplier.SupplierPaymentResponse;
import com.chala.posapp.dto.supplier.SupplierQuickCreateRequest;
import com.chala.posapp.dto.supplier.SupplierResponse;
import com.chala.posapp.dto.supplier.SupplierUpdateRequest;
import com.chala.posapp.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/suppliers", "/api/suppliers"})
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierCreateRequest req) {
        return ResponseEntity.ok(supplierService.create(req));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PostMapping("/quick")
    public ResponseEntity<SupplierResponse> createQuick(@Valid @RequestBody SupplierQuickCreateRequest req) {
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

    // Roles mirror create() exactly, so the role axis and the module gate stay in step:
    // the SUPPLIERS module in ModuleCatalog already claims /suppliers and /suppliers/**,
    // so a new route on this controller is gated the moment it is added.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<SupplierResponse> update(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody SupplierUpdateRequest req
    ) {
        return ResponseEntity.ok(supplierService.update(id, req));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable(name = "id") Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
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
