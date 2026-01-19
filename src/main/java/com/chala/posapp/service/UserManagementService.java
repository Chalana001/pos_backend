package com.chala.posapp.service;

import com.chala.posapp.dto.*;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponse createUser(CreateUserRequest request) {
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username))
            throw new RuntimeException("Username already exists");

        Role role = request.getRole();

        // MANAGER/CASHIER must have branch
        if ((role == Role.MANAGER || role == Role.CASHIER) && request.getBranchId() == null)
            throw new RuntimeException("BranchId required for " + role);

        // If branchId provided, validate branch exists & active
        Long branchId = null;
        if (request.getBranchId() != null) {
            Branch branch = branchRepository.findById(request.getBranchId())
                    .orElseThrow(() -> new RuntimeException("Branch not found"));

            if (!branch.isActive())
                throw new RuntimeException("Branch inactive");

            branchId = branch.getId();
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .enabled(true)
                .branchId(branchId)
                .build();

        return map(userRepository.save(user));
    }

    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().map(this::map).toList();
    }

    public UserResponse getUser(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return map(u);
    }

    @Transactional
    public String assignBranch(Long userId, AssignBranchRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new RuntimeException("Branch not found"));

        if (!branch.isActive())
            throw new RuntimeException("Branch inactive");

        user.setBranchId(branch.getId());
        return "Branch assigned";
    }

    @Transactional
    public String updateUserStatus(Long userId, UpdateUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setEnabled(request.getEnabled());
        return "User status updated";
    }

    @Transactional
    public String resetPassword(Long userId, ResetPasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        return "Password reset successful";
    }

    private UserResponse map(User u) {
        return UserResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .role(u.getRole())
                .enabled(u.isEnabled())
                .branchId(u.getBranchId())
                .createdAt(u.getCreatedAt())
                .build();
    }
}
