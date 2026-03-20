package com.chala.posapp.service;

import com.chala.posapp.dto.branch.AssignBranchRequest;
import com.chala.posapp.dto.user.CreateUserRequest;
import com.chala.posapp.dto.user.ResetPasswordRequest;
import com.chala.posapp.dto.user.UpdateUserStatusRequest;
import com.chala.posapp.dto.user.UserResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.UserRepository;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponse createUser(CreateUserRequest request) {
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username))
            throw new AlreadyExistsException("Username already exists");

        Role role = request.getRole();

        if ((role == Role.MANAGER || role == Role.CASHIER) && request.getBranchId() == null)
            throw new BadRequestException("BranchId required for " + role);

        Long branchId = null;
        if (request.getBranchId() != null) {
            Branch branch = branchRepository.findById(request.getBranchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

            if (!branch.isActive())
                throw new BadRequestException("Branch inactive");

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
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return map(u);
    }

    @Transactional
    public String assignBranch(Long userId, AssignBranchRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (userId == 1L || user.getRole() == Role.ADMIN) {
            throw new BadRequestException("Admin user cannot assign branch");
        }

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        if (!branch.isActive())
            throw new BadRequestException("Branch inactive");

        user.setBranchId(branch.getId());
        return "Branch assigned";
    }

    @Transactional
    public String updateUserStatus(Long userId, UpdateUserStatusRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (userId == 1L || user.getRole() == Role.ADMIN) {
            throw new BadRequestException("Admin user cannot be disabled");
        }

        user.setEnabled(request.getEnabled());
        return "User status updated";
    }

    @Transactional
    public String resetPassword(Long userId, ResetPasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

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

    public @Nullable List<UserResponse> getCashiersInBranch(Long branchId) {
        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }
        List<User> cashiers = userRepository.findAllByBranchId(branchId);

        System.out.println(cashiers);
        return cashiers.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }
}
