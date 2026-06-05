package com.chala.posapp.service;

import com.chala.posapp.dto.dining.DiningTableCreateRequest;
import com.chala.posapp.dto.dining.DiningTableResponse;
import com.chala.posapp.dto.dining.DiningTableUpdateRequest;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.DiningTable;
import com.chala.posapp.entity.DiningTableStatus;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.TenantSubscription;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.NotAssignedException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.DiningTableRepository;
import com.chala.posapp.repository.PendingOrderRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.repository.UserRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiningTableService {

    private final DiningTableRepository diningTableRepository;
    private final PendingOrderRepository pendingOrderRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final AppConfigurationService appConfigurationService;

    private User getLoggedUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private boolean isAdminLike(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    private Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null) {
            throw new NotAssignedException("User branch not assigned");
        }
        return user.getBranchId();
    }

    private void ensureBranchAccess(User user, Long branchId) {
        if (isAdminLike(user)) {
            return;
        }

        Long userBranchId = requireAssignedBranch(user);
        if (!userBranchId.equals(branchId)) {
            throw new BadRequestException("Cannot access another branch");
        }
    }

    @Transactional
    public DiningTableResponse create(DiningTableCreateRequest request) {
        User user = getLoggedUser();
        validateDiningTableFeature(request.getBranchId());
        ensureBranchAccess(user, request.getBranchId());

        Branch branch = branchRepository.findById(request.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        if (!branch.isActive()) {
            throw new BadRequestException("Branch is inactive");
        }

        String tableName = request.getTableName().trim();
        if (diningTableRepository.existsByBranchIdAndTableNameIgnoreCase(request.getBranchId(), tableName)) {
            throw new BadRequestException("Dining table already exists in this branch");
        }

        DiningTable table = DiningTable.builder()
                .branchId(request.getBranchId())
                .tableName(tableName)
                .status(request.getStatus() == null ? DiningTableStatus.AVAILABLE : request.getStatus())
                .build();

        return map(diningTableRepository.save(table));
    }

    public List<DiningTableResponse> listByBranch(Long branchId) {
        User user = getLoggedUser();
        validateDiningFeatureEnabled(branchId);
        ensureBranchAccess(user, branchId);

        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));

        return diningTableRepository.findByBranchIdOrderByTableNameAsc(branchId).stream()
                .map(this::map)
                .toList();
    }

    private void validateDiningTableFeature(Long branchId) {
        if (!appConfigurationService.isTableManagementEnabled(branchId)) {
            throw new BadRequestException("Table management is disabled in app configuration");
        }

        String planName = currentPlanName();
        if (isFreePlan(planName)) {
            throw new BadRequestException("Tables are not available in FREE plan");
        }
        if (isStandardPlan(planName) && diningTableRepository.countByBranchId(branchId) >= 15) {
            throw new BadRequestException("STANDARD plan supports maximum 15 tables");
        }
    }

    private void validateDiningFeatureEnabled(Long branchId) {
        if (!appConfigurationService.isTableManagementEnabled(branchId)) {
            throw new BadRequestException("Table management is disabled in app configuration");
        }

        if (isFreePlan(currentPlanName())) {
            throw new BadRequestException("Tables are not available in FREE plan");
        }
    }

    private String currentPlanName() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return "";
        }
        return tenantSubscriptionRepository.findByTenantId(tenantId)
                .map(TenantSubscription::getPlan)
                .map(plan -> plan.getName() == null ? "" : plan.getName().trim().toUpperCase())
                .orElse("");
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

    public DiningTableResponse get(Long id) {
        User user = getLoggedUser();
        DiningTable table = diningTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        ensureBranchAccess(user, table.getBranchId());
        return map(table);
    }

    @Transactional
    public DiningTableResponse update(Long id, DiningTableUpdateRequest request) {
        User user = getLoggedUser();
        DiningTable table = diningTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        ensureBranchAccess(user, table.getBranchId());

        if (request.getTableName() != null && !request.getTableName().isBlank()) {
            String tableName = request.getTableName().trim();
            if (diningTableRepository.existsByBranchIdAndTableNameIgnoreCaseAndIdNot(table.getBranchId(), tableName, table.getId())) {
                throw new BadRequestException("Dining table already exists in this branch");
            }
            table.setTableName(tableName);
        }

        if (request.getStatus() != null) {
            if (request.getStatus() == DiningTableStatus.AVAILABLE && pendingOrderRepository.findByTableId(table.getId()).isPresent()) {
                throw new BadRequestException("Cannot mark table as AVAILABLE while a pending order exists");
            }
            table.setStatus(request.getStatus());
        }

        return map(diningTableRepository.save(table));
    }

    @Transactional
    public void delete(Long id) {
        User user = getLoggedUser();
        DiningTable table = diningTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dining table not found"));
        ensureBranchAccess(user, table.getBranchId());

        if (pendingOrderRepository.findByTableId(id).isPresent()) {
            throw new BadRequestException("Cannot delete a dining table with a pending order");
        }

        diningTableRepository.delete(table);
    }

    private DiningTableResponse map(DiningTable table) {
        return DiningTableResponse.builder()
                .id(table.getId())
                .branchId(table.getBranchId())
                .tableName(table.getTableName())
                .status(table.getStatus())
                .build();
    }
}
