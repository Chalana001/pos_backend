package com.chala.posapp.controller;

import com.chala.posapp.dto.branch.BranchCreateRequest;
import com.chala.posapp.dto.branch.BranchResponse;
import com.chala.posapp.dto.branch.BranchUpdateRequest;
import com.chala.posapp.service.BranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    // ADMIN only
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<BranchResponse> create(@Valid @RequestBody BranchCreateRequest request) {
        return ResponseEntity.ok(branchService.createBranch(request));
    }

    // ADMIN/MANAGER can view
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/{id}")
    public ResponseEntity<BranchResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(branchService.getBranch(id));
    }

    // ADMIN/MANAGER can list
    // /branches?activeOnly=true
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','CASHIER')")
    @GetMapping
    public ResponseEntity<List<BranchResponse>> list(@RequestParam(name = "activeOnly", required = false) Boolean activeOnly) {
        System.out.println("called branch in controller");
        return ResponseEntity.ok(branchService.getAllBranches(activeOnly));
    }

    // ADMIN only
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<BranchResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody BranchUpdateRequest request
    ) {
        return ResponseEntity.ok(branchService.updateBranch(id, request));
    }

    // ADMIN only - deactivate branch
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable Long id) {
        branchService.deleteBranch(id);
        return ResponseEntity.ok("Branch deactivated");
    }
}
