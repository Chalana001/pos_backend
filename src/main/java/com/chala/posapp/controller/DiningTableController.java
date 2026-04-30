package com.chala.posapp.controller;

import com.chala.posapp.dto.dining.DiningTableCreateRequest;
import com.chala.posapp.dto.dining.DiningTableResponse;
import com.chala.posapp.dto.dining.DiningTableUpdateRequest;
import com.chala.posapp.service.DiningTableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dining-tables")
@RequiredArgsConstructor
public class DiningTableController {

    private final DiningTableService diningTableService;

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping
    public ResponseEntity<DiningTableResponse> create(@Valid @RequestBody DiningTableCreateRequest request) {
        return ResponseEntity.ok(diningTableService.create(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<DiningTableResponse>> list(@RequestParam Long branchId) {
        return ResponseEntity.ok(diningTableService.listByBranch(branchId));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping("/{id}")
    public ResponseEntity<DiningTableResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(diningTableService.get(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PutMapping("/{id}")
    public ResponseEntity<DiningTableResponse> update(@PathVariable Long id, @Valid @RequestBody DiningTableUpdateRequest request) {
        return ResponseEntity.ok(diningTableService.update(id, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        diningTableService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
