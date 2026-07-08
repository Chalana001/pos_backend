package com.chala.posapp.service;

import com.chala.posapp.dto.branch.AssignBranchRequest;
import com.chala.posapp.dto.user.CreateUserRequest;
import com.chala.posapp.dto.user.ResetPasswordRequest;
import com.chala.posapp.dto.user.UpdateUserStatusRequest;
import com.chala.posapp.dto.user.UserResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.AlreadyExistsException;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import com.chala.posapp.audit.Audited;
import com.chala.posapp.util.SecurityUtils;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserManagementService {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PlatformTransactionManager transactionManager;
    private final SecurityUtils securityUtils;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser() — use SecurityUtils instead

    @Audited(entity = "USER", action = "CREATE",
             summaryExpression = "'username=' + #request.username + ' role=' + #request.role")
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username))
            throw new AlreadyExistsException("Username already exists");

        Role role = request.getRole();
        validateUserLimit(role);

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

    @Transactional(readOnly = true)
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

    @Audited(entity = "USER", action = "RESET_PASSWORD",
             idExpression = "#userId",
             summaryExpression = "'userId=' + #userId")
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

    // MISS-07: Removed debug System.out.println — use structured logging if needed
    public @Nullable List<UserResponse> getCashiersInBranch(Long branchId) {
        if (!branchRepository.existsById(branchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }
        return userRepository.findAllByBranchId(branchId).stream()
                .map(this::map)
                .collect(Collectors.toList());
    }

    private void validateUserLimit(Role role) {
        String planName = currentPlanName();
        if (isFreePlan(planName) && (role == Role.MANAGER || role == Role.CASHIER)) {
            throw new BadRequestException("FREE plan supports admin user only");
        }

        if (isStandardPlan(planName) && role == Role.CASHIER && userRepository.countByRoleAndEnabledTrue(Role.CASHIER) >= 2) {
            throw new BadRequestException("STANDARD plan supports maximum 2 cashiers");
        }
    }

    private String currentPlanName() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return "";
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        String plan = TenantContext.callWith("MASTER",
                () -> tx.execute(status ->
                        tenantSubscriptionRepository.findByTenantId(tenantId)
                                .map(TenantSubscription::getPlan)
                                .map(p -> p.getName() == null ? "" : p.getName().trim().toUpperCase())
                                .orElse("")
                ));
        return plan == null ? "" : plan;
    }

    private boolean isFreePlan(String planName) {
        return "FREE".equals(planName) || "MONTHLY_DEMO".equals(planName);
    }

    private boolean isStandardPlan(String planName) {
        return "STANDARD".equals(planName)
                || "MONTHLY_LITE".equals(planName)
                || "YEARLY_LITE".equals(planName)
                || "MONTHLY_BASIC".equals(planName);
    }

    public List<UserResponse> getSalesFilterUsers(Long branchId) {
        User currentUser = securityUtils.getCurrentUser();
        Long effectiveBranchId = branchId != null && branchId > 0 ? branchId : null;

        if (currentUser.getRole() == Role.MANAGER) {
            if (currentUser.getBranchId() == null) {
                throw new BadRequestException("User branch not assigned");
            }
            if (effectiveBranchId != null && !effectiveBranchId.equals(currentUser.getBranchId())) {
                throw new BadRequestException("Cannot access another branch");
            }
            effectiveBranchId = currentUser.getBranchId();
        }

        if (effectiveBranchId != null && !branchRepository.existsById(effectiveBranchId)) {
            throw new ResourceNotFoundException("Branch not found in the system");
        }

        return userRepository.findSalesFilterUsers(
                        List.of(Role.ADMIN, Role.MANAGER, Role.CASHIER),
                        effectiveBranchId
                )
                .stream()
                .map(this::map)
                .toList();
    }
}
