package com.chala.posapp.controller;

import com.chala.posapp.dto.CreatePurchaseRequest;
import com.chala.posapp.dto.PurchaseResponse;
import com.chala.posapp.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    // 1. Save Method
    @PostMapping
    public ResponseEntity<PurchaseResponse> create(@RequestBody CreatePurchaseRequest request) {
        return ResponseEntity.ok(purchaseService.createPurchase(request));
    }

    @GetMapping
    public ResponseEntity<Page<PurchaseResponse>> getAll(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(purchaseService.getAllPurchases(page, size));
    }

    // 3. GET BY ID Method (GRN List එකත් එක්ක යවන එක)
    // 👇 මෙන්න මේ විදියට (name = "id") එකතු කරන්න
    @GetMapping("/{id}")
    public ResponseEntity<PurchaseResponse> getById(@PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(purchaseService.getPurchaseById(id));
    }
}