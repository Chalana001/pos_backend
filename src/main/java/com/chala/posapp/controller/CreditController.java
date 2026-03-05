package com.chala.posapp.controller;

import com.chala.posapp.dto.payments.CreditPaymentRequest;
import com.chala.posapp.dto.payments.CreditPaymentResponse;
import com.chala.posapp.service.CreditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/credit")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;

    // settle due payment
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping("/settle")
    public ResponseEntity<CreditPaymentResponse> settle(@Valid @RequestBody CreditPaymentRequest request) {
        return ResponseEntity.ok(creditService.settleCredit(request));
    }

    // payment history
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/payments/{customerId}")
    public ResponseEntity<List<CreditPaymentResponse>> history(@PathVariable Long customerId) {
        return ResponseEntity.ok(creditService.paymentHistory(customerId));
    }
}
