package com.chala.posapp.controller;

import com.chala.posapp.dto.SupplierCreateRequest;
import com.chala.posapp.dto.SupplierQuickCreateRequest;
import com.chala.posapp.dto.SupplierResponse;
import com.chala.posapp.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SupplierCreateRequest req) {
        return ResponseEntity.ok(supplierService.create(req));
    }

    @PostMapping("/quick")
    public ResponseEntity<?> createQuick(@RequestBody SupplierQuickCreateRequest req) {
        return ResponseEntity.ok(supplierService.createQuickSupplier(req));
    }

    @GetMapping
    public ResponseEntity<List<SupplierResponse>> getAll() {
        return ResponseEntity.ok(supplierService.getAllActiveSuppliers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.getSupplierById(id));
    }

}
