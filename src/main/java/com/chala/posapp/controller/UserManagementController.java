package com.chala.posapp.controller;

import com.chala.posapp.dto.*;
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

    // ✅ Create cashier/manager
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(userManagementService.createUser(request));
    }

    // ✅ list all users
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userManagementService.listUsers());
    }

    // ✅ get user by id
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(userManagementService.getUser(id));
    }

    // ✅ assign branch
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/assign-branch")
    public ResponseEntity<?> assignBranch(@PathVariable Long userId,
                                          @Valid @RequestBody AssignBranchRequest request) {
        return ResponseEntity.ok(userManagementService.assignBranch(userId, request));
    }

    // ✅ enable / disable
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long userId,
                                          @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateUserStatus(userId, request));
    }

    // ✅ reset password
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userId}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long userId,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(userManagementService.resetPassword(userId, request));
    }
}
