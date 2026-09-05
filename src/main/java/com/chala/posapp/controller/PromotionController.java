package com.chala.posapp.controller;

import com.chala.posapp.dto.promotion.*;
import com.chala.posapp.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /**
     * Prices a proposed item list without saving it. Drives the margin badges in the builder,
     * so a mistyped price is caught while it is being typed rather than at the till.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/price-check")
    public ResponseEntity<PromotionPriceCheckResponse> priceCheck(@Valid @RequestBody PromotionPriceCheckRequest request) {
        return ResponseEntity.ok(promotionService.priceCheck(request));
    }

    /** Would this code work right now? Answered without consuming anything, for the till. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/check-code")
    public ResponseEntity<CodeCheckResponse> checkCode(@Valid @RequestBody CodeCheckRequest request) {
        return ResponseEntity.ok(promotionService.checkCode(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{id}/codes")
    public ResponseEntity<List<PromotionCodeDto>> listCodes(@PathVariable Long id) {
        return ResponseEntity.ok(promotionService.listCodes(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{id}/codes")
    public ResponseEntity<List<PromotionCodeDto>> generateCodes(
            @PathVariable Long id,
            @Valid @RequestBody GenerateCodesRequest request
    ) {
        return ResponseEntity.ok(promotionService.generateCodes(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PatchMapping("/{id}/codes/{codeId}/status")
    public ResponseEntity<PromotionCodeDto> setCodeActive(
            @PathVariable Long id,
            @PathVariable Long codeId,
            @RequestBody PromotionStatusRequest request
    ) {
        return ResponseEntity.ok(promotionService.setCodeActive(id, codeId, request.isActive()));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<PromotionResponse> duplicate(@PathVariable Long id) {
        return ResponseEntity.ok(promotionService.duplicate(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/history")
    public ResponseEntity<List<PromotionHistoryResponse>> history(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(promotionService.history(branchId, from, to));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/redemptions")
    public ResponseEntity<List<PromotionRedemptionResponse>> redemptions(
            @RequestParam(required = false) Long promotionId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ResponseEntity.ok(promotionService.redemptions(promotionId, branchId, from, to, page, size));
    }
}
