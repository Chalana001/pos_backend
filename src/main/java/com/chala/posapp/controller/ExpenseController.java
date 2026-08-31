package com.chala.posapp.controller;

import com.chala.posapp.dto.CreateExpenseRequest;
import com.chala.posapp.dto.ExpenseResponse;
import com.chala.posapp.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

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

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<Page<ExpenseResponse>> getExpenses(
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "cashierId", required = false) Long cashierId,
            @RequestParam(name = "expenseTypeId", required = false) Long expenseTypeId,
            @RequestParam(name = "shiftId", required = false) Long shiftId,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(expenseService.getFilteredExpenses(branchId, cashierId, expenseTypeId, shiftId, search, from, to, pageable));
    }

}
