package com.chala.posapp.controller;

import com.chala.posapp.dto.ExpenseTypeDeleteResponse;
import com.chala.posapp.dto.ExpenseTypeRequest;
import com.chala.posapp.dto.ExpenseTypeResponse;
import com.chala.posapp.service.ExpenseTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/expense-types")
@RequiredArgsConstructor
public class ExpenseTypeController {

    private final ExpenseTypeService expenseTypeService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/active")
    public ResponseEntity<List<ExpenseTypeResponse>> listActive() {
        return ResponseEntity.ok(expenseTypeService.listActive());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<ExpenseTypeResponse>> listAll() {
        return ResponseEntity.ok(expenseTypeService.listAll());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PostMapping
    public ResponseEntity<ExpenseTypeResponse> create(@Valid @RequestBody ExpenseTypeRequest request) {
        return ResponseEntity.ok(expenseTypeService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @PutMapping("/{id}")
    public ResponseEntity<ExpenseTypeResponse> update(@PathVariable Long id, @Valid @RequestBody ExpenseTypeRequest request) {
        return ResponseEntity.ok(expenseTypeService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ExpenseTypeDeleteResponse> delete(@PathVariable Long id) {
        return ResponseEntity.ok(expenseTypeService.delete(id));
    }
}
