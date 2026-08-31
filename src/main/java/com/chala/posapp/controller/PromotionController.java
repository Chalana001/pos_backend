package com.chala.posapp.controller;

import com.chala.posapp.dto.promotion.*;
import com.chala.posapp.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<PromotionResponse>> list() {
        return ResponseEntity.ok(promotionService.list());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(promotionService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<PromotionResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody PromotionRequest request
    ) {
        return ResponseEntity.ok(promotionService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<PromotionResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody PromotionStatusRequest request
    ) {
        return ResponseEntity.ok(promotionService.updateStatus(id, request.isActive()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        promotionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/preview")
    public ResponseEntity<PromotionPreviewResponse> preview(@Valid @RequestBody PromotionPreviewRequest request) {
        return ResponseEntity.ok(promotionService.preview(request));
    }
}
