package com.chala.posapp.controller;

import com.chala.posapp.dto.branch.AssignBranchRequest;
import com.chala.posapp.dto.user.CreateUserRequest;
import com.chala.posapp.dto.user.ResetPasswordRequest;
import com.chala.posapp.dto.user.UpdateUserStatusRequest;
import com.chala.posapp.dto.user.UserResponse;
import com.chala.posapp.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(userManagementService.createUser(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userManagementService.listUsers());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-filter")
    public ResponseEntity<List<UserResponse>> salesFilterUsers(
            @RequestParam(value = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(userManagementService.getSalesFilterUsers(branchId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable("id") Long id) { // Noma danna
        return ResponseEntity.ok(userManagementService.getUser(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/assign-branch")
    public ResponseEntity<?> assignBranch(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody AssignBranchRequest request) {
        return ResponseEntity.ok(userManagementService.assignBranch(userId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateUserStatus(userId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/reset-password")
    public ResponseEntity<?> resetPassword(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(userManagementService.resetPassword(userId, request));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<UserResponse>> branchCashiers(@PathVariable("branchId") Long branchId) {
        return ResponseEntity.ok(userManagementService.getCashiersInBranch(branchId));
    }
}
