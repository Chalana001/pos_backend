package com.chala.posapp.controller;

import com.chala.posapp.dto.branch.AssignBranchRequest;
import com.chala.posapp.dto.user.CreateUserRequest;
import com.chala.posapp.dto.user.ResetPasswordRequest;
import com.chala.posapp.dto.user.UpdateUserStatusRequest;
import com.chala.posapp.dto.user.UserResponse;
import com.chala.posapp.service.UserManagementService;
import com.chala.posapp.rbac.RbacAuthorizationService;
import com.chala.posapp.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/users", "/api/users"})
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;
    private final RbacAuthorizationService rbacAuthorizationService;
    private final SecurityUtils securityUtils;

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.createUser(request));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.listUsers());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/sales-filter")
    public ResponseEntity<List<UserResponse>> salesFilterUsers(
            @RequestParam(value = "branchId", required = false) Long branchId
    ) {
        return ResponseEntity.ok(userManagementService.getSalesFilterUsers(branchId));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable("id") Long id) { // Noma danna
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.getUser(id));
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{userId}/assign-branch")
    public ResponseEntity<?> assignBranch(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody AssignBranchRequest request) {
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.assignBranch(userId, request));
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{userId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody UpdateUserStatusRequest request) {
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.updateUserStatus(userId, request));
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{userId}/reset-password")
    public ResponseEntity<?> resetPassword(
            @PathVariable("userId") Long userId, // Noma danna
            @Valid @RequestBody ResetPasswordRequest request) {
        requireOperationalManager();
        return ResponseEntity.ok(userManagementService.resetPassword(userId, request));
    }

    private void requireOperationalManager() {
        rbacAuthorizationService.requireOperationalRoleManager(securityUtils.getCurrentUser());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @GetMapping("/branch/{branchId}")
    public ResponseEntity<List<UserResponse>> branchCashiers(@PathVariable("branchId") Long branchId) {
        return ResponseEntity.ok(userManagementService.getCashiersInBranch(branchId));
    }
}
