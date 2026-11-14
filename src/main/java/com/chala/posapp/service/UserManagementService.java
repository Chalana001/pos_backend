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
import com.chala.posapp.rbac.TenantAdminFloorService;
import com.chala.posapp.rbac.TenantRbacJdbcService;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final TenantAdminFloorService adminFloorService;
    private final TenantRbacJdbcService tenantRbacJdbcService;

    // BUG-07/08 FIX: Removed duplicate securityUtils.getCurrentUser(). Use SecurityUtils instead

    @Audited(entity = "USER", action = "CREATE",
             summaryExpression = "'username=' + #request.username + ' role=' + #request.role")
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username))
            throw new AlreadyExistsException("Username already exists");

        Role role = request.getRole();
        if (role == Role.CUSTOM || role == Role.SUPER_ADMIN) {
            throw new BadRequestException("Tenant user creation cannot create platform or custom roles");
        }
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

        String passwordHash = passwordEncoder.encode(request.getPassword());
        if (tenantRbacJdbcService.readyForCurrentTenant()) {
            TenantRbacJdbcService.TenantUserCreateResult created = tenantRbacJdbcService.createReadyTenantUser(
                    username, passwordHash, role.name(), true, branchId);
            return UserResponse.builder()
                    .id(created.userId())
                    .username(username)
                    .role(role)
                    .enabled(true)
                    .branchId(branchId)
                    .roleId(created.roleId())
                    .rbacVersion(created.rbacVersion())
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordHash)
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

        if (tenantRbacJdbcService.readyForCurrentTenant()) {
            adminFloorService.withAdminFloorLock(jdbc -> {
                adminFloorService.requireAdminFloorAfterStatusChange(jdbc, userId, request.getEnabled());
                int updated = jdbc.update("UPDATE users SET enabled = ? WHERE id = ?", request.getEnabled(), userId);
                if (updated != 1) throw new ResourceNotFoundException("User not found");
                return null;
            });
        } else {
            tenantRbacJdbcService.updateLegacyUserStatus(userId, request.getEnabled());
        }
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
        // Cut every session this user already has. Resetting the password of someone who has
        // been compromised is pointless while their existing token keeps working.
        user.setTokenValidFrom(LocalDateTime.now());
        return "Password reset successful";
    }

    private UserResponse map(User u) {
        Long roleId = u.getRoleId();
        Long rbacVersion = u.getRbacVersion();
        if (tenantRbacJdbcService.readyForCurrentTenant()) {
            Map<String, Object> state = tenantRbacJdbcService.withTenantJdbc(jdbc -> jdbc.queryForMap(
                    "SELECT role_id, COALESCE(rbac_version, 0) AS rbac_version FROM users WHERE id = ?", u.getId()));
            roleId = ((Number) state.get("role_id")).longValue();
            rbacVersion = ((Number) state.get("rbac_version")).longValue();
        }
        return UserResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .role(u.getRole())
                .enabled(u.isEnabled())
                .branchId(u.getBranchId())
                .roleId(roleId)
                .rbacVersion(rbacVersion)
                .createdAt(u.getCreatedAt())
                .build();
    }

    // MISS-07: Removed debug System.out.println. Use structured logging if needed
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
