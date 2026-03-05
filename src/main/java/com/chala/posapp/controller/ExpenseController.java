package com.chala.posapp.controller;

import com.chala.posapp.dto.CreateExpenseRequest;
import com.chala.posapp.dto.ExpenseResponse;
import com.chala.posapp.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<ExpenseResponse> addExpense(@Valid @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.ok(expenseService.addExpense(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> getExpenses(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "from") Instant from,
            @RequestParam(name = "to") Instant to) {
        return ResponseEntity.ok(expenseService.getExpenses(branchId, from, to));
    }

}