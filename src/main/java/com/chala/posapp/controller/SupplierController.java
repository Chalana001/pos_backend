package com.chala.posapp.controller;

import com.chala.posapp.dto.SupplierCreateRequest;
import com.chala.posapp.dto.SupplierQuickCreateRequest;
import com.chala.posapp.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/supplier")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @PostMapping("/suppliers")
    public ResponseEntity<?> create(@RequestBody SupplierCreateRequest req) {
        return ResponseEntity.ok(supplierService.create(req));
    }

    @PostMapping("/suppliers/quick")
    public ResponseEntity<?> createQuick(@RequestBody SupplierQuickCreateRequest req) {
        return ResponseEntity.ok(supplierService.createQuickSupplier(req));
    }

}
