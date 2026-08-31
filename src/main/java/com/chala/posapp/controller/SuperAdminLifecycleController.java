package com.chala.posapp.controller;

import com.chala.posapp.dto.saas.LifecycleDtos.DiscountRequest;
import com.chala.posapp.entity.DiscountCode;
import com.chala.posapp.entity.DiscountRedemption;
import com.chala.posapp.entity.SubscriptionInvoice;
import com.chala.posapp.service.DiscountService;
import com.chala.posapp.service.SubscriptionInvoiceService;
import com.chala.posapp.service.SubscriptionLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Money and lifecycle: discount codes, subscription invoices, and the renewal queue.
 */
@RestController
@RequestMapping("/api/saas/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminLifecycleController {

    private final DiscountService discountService;
    private final SubscriptionInvoiceService invoiceService;
    private final SubscriptionLifecycleService lifecycleService;

    // -------------------------------------------------------- discount codes

    @GetMapping("/discounts")
    public ResponseEntity<List<DiscountCode>> discounts() {
        return ResponseEntity.ok(discountService.list());
    }

    @GetMapping("/discounts/{id}")
    public ResponseEntity<DiscountCode> discount(@PathVariable Long id) {
        return ResponseEntity.ok(discountService.get(id));
    }

    @GetMapping("/discounts/{id}/redemptions")
    public ResponseEntity<List<DiscountRedemption>> redemptions(@PathVariable Long id) {
        return ResponseEntity.ok(discountService.redemptions(id));
    }

    @PostMapping("/discounts")
    public ResponseEntity<DiscountCode> createDiscount(@Valid @RequestBody DiscountRequest request) {
        return ResponseEntity.ok(discountService.create(request));
    }

    @PutMapping("/discounts/{id}")
    public ResponseEntity<DiscountCode> updateDiscount(@PathVariable Long id,
                                                       @Valid @RequestBody DiscountRequest request) {
        return ResponseEntity.ok(discountService.update(id, request));
    }

    @DeleteMapping("/discounts/{id}")
    public ResponseEntity<Void> deleteDiscount(@PathVariable Long id) {
        discountService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * What a code would do to a given amount, without consuming it — so the onboarding and
     * renewal forms can show the discounted figure live.
     */
    @GetMapping("/discounts/preview")
    public ResponseEntity<DiscountService.DiscountPreview> previewDiscount(
            @RequestParam String code,
            @RequestParam double amount,
            @RequestParam(required = false) Long planId
    ) {
        return ResponseEntity.ok(discountService.preview(code, amount, planId));
    }

    // ------------------------------------------------------------- invoices

    @GetMapping("/shops/{tenantId}/invoices")
    public ResponseEntity<List<SubscriptionInvoice>> invoices(@PathVariable String tenantId) {
        return ResponseEntity.ok(invoiceService.forTenant(tenantId));
    }

    /** Issues an invoice for an existing payment. Idempotent per billing record. */
    @PostMapping("/billing/{billingRecordId}/invoice")
    public ResponseEntity<SubscriptionInvoice> issueInvoice(
            @PathVariable Long billingRecordId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String notes = body == null ? null : body.get("notes");
        return ResponseEntity.ok(invoiceService.issueFor(billingRecordId, notes));
    }

    @PostMapping("/invoices/{id}/void")
    public ResponseEntity<SubscriptionInvoice> voidInvoice(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(invoiceService.voidInvoice(id, body == null ? null : body.get("reason")));
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        SubscriptionInvoice invoice = invoiceService.get(id);
        byte[] pdf = invoiceService.renderPdf(id);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                // "inline" so the panel can preview it in a tab; the browser's own download
                // button still saves it under this name.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + invoice.getInvoiceNo() + ".pdf\"")
                .body(pdf);
    }

    // ------------------------------------------------------- renewal queue

    @GetMapping("/renewals")
    public ResponseEntity<SubscriptionLifecycleService.RenewalQueue> renewals() {
        return ResponseEntity.ok(lifecycleService.renewalQueue());
    }

    /** Records that a shop has been chased, so two operators do not chase the same one. */
    @PostMapping("/shops/{tenantId}/renewal-reminder")
    public ResponseEntity<Void> markReminded(@PathVariable String tenantId,
                                             @RequestBody(required = false) Map<String, String> body) {
        lifecycleService.markReminded(tenantId,
                body == null ? null : body.get("reminderType"),
                body == null ? null : body.get("note"));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/shops/{tenantId}/grace")
    public ResponseEntity<Void> setGrace(@PathVariable String tenantId,
                                         @RequestBody Map<String, Integer> body) {
        lifecycleService.setGraceDays(tenantId, body.getOrDefault("graceDays", 0));
        return ResponseEntity.noContent().build();
    }
}
