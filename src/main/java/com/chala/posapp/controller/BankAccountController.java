package com.chala.posapp.controller;

import com.chala.posapp.dto.BankAccountDeleteResponse;
import com.chala.posapp.dto.BankAccountRequest;
import com.chala.posapp.dto.BankAccountResponse;
import com.chala.posapp.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;

    // Every role can read — cashiers need this list to pick a bank account
    // when recording their own in-shift cash drop.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/active")
    public ResponseEntity<List<BankAccountResponse>> listActive() {
        return ResponseEntity.ok(bankAccountService.listActive());
    }

    // Management list (includes inactive ones) — Admin/Manager only.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<BankAccountResponse>> listAll() {
        return ResponseEntity.ok(bankAccountService.listAll());
    }

    // Single account's own profile page — same access level as the
    // management list it's reached from.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<BankAccountResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(bankAccountService.getById(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<BankAccountResponse> create(@Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(bankAccountService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<BankAccountResponse> update(@PathVariable Long id, @Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(bankAccountService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<BankAccountDeleteResponse> delete(@PathVariable Long id) {
        return ResponseEntity.ok(bankAccountService.delete(id));
    }
}
