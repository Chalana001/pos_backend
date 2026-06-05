package com.chala.posapp.service;

import com.chala.posapp.dto.configuration.AppConfigurationRequest;
import com.chala.posapp.dto.configuration.AppConfigurationResponse;
import com.chala.posapp.entity.AppConfiguration;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Role;
import com.chala.posapp.entity.StockOverrideMode;
import com.chala.posapp.entity.User;
import com.chala.posapp.exception.BadRequestException;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.AppConfigurationRepository;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.TenantSubscriptionRepository;
import com.chala.posapp.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppConfigurationService {

    private final AppConfigurationRepository appConfigurationRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final BranchRepository branchRepository;
    private final AuthService authService;

    public AppConfigurationResponse getConfiguration(Long requestedBranchId) {
        Long branchId = resolveRequestedBranchId(requestedBranchId, false);
        return mapEffective(getOrDefault(branchId), branchId);
    }

    @Transactional
    public AppConfigurationResponse updateConfiguration(Long requestedBranchId, AppConfigurationRequest request) {
        Long branchId = resolveRequestedBranchId(requestedBranchId != null ? requestedBranchId : request.getBranchId(), true);
        AppConfiguration configuration = branchId == null
                ? appConfigurationRepository.findByTenantIdAndBranchIdIsNull(currentTenantId()).orElseGet(this::buildDefaultConfiguration)
                : appConfigurationRepository.findByTenantIdAndBranchId(currentTenantId(), branchId)
                .orElseGet(() -> buildScopedConfiguration(branchId, getOrDefault(null)));

        configuration.setBranchId(branchId);
        applyRequest(configuration, request);
        return mapEffective(appConfigurationRepository.save(configuration), branchId);
    }

    public boolean isItemTypeEnabled(ItemType itemType) {
        return isItemTypeEnabled(itemType, resolveRuntimeBranchId());
    }

    public boolean isItemTypeEnabled(ItemType itemType, Long branchId) {
        AppConfiguration configuration = getOrDefault(branchId);
        return switch (itemType) {
            case NORMAL -> true;
            case WEIGHT -> planSupportsWeightItems() && configuration.isWeightItemsEnabled();
            case VOLUME -> planSupportsWeightItems() && configuration.isWeightItemsEnabled();
            case SERVICE -> planSupportsServices() && configuration.isServicesEnabled();
            case RECIPE -> planSupportsRecipeItems() && configuration.isRecipeItemsEnabled();
        };
    }

    public boolean isTableManagementEnabled() {
        return isTableManagementEnabled(resolveRuntimeBranchId());
    }

    public boolean isTableManagementEnabled(Long branchId) {
        return planSupportsDiningTables() && getOrDefault(branchId).isTableManagementEnabled();
    }

    public boolean isDineInEnabled() {
        return isDineInEnabled(resolveRuntimeBranchId());
    }

    public boolean isDineInEnabled(Long branchId) {
        AppConfiguration configuration = getOrDefault(branchId);
        return planSupportsDiningTables() && configuration.isTableManagementEnabled() && configuration.isDineInEnabled();
    }

    public StockOverrideMode getStockOverrideMode() {
        return getStockOverrideMode(resolveRuntimeBranchId());
    }

    public StockOverrideMode getStockOverrideMode(Long branchId) {
        StockOverrideMode mode = getOrDefault(branchId).getStockOverrideMode();
        return mode != null ? mode : StockOverrideMode.MANAGER_OVERRIDE;
    }

    public boolean isStockOverrideAllowedForRole(Role role) {
        return isStockOverrideAllowedForRole(role, resolveRuntimeBranchId());
    }

    public boolean isStockOverrideAllowedForRole(Role role, Long branchId) {
        if (role == Role.SUPER_ADMIN) {
            return true;
        }

        AppConfiguration configuration = getOrDefault(branchId);
        return switch (role) {
            case ADMIN -> configuration.isAdminStockOverrideAllowed();
            case MANAGER -> configuration.isManagerStockOverrideAllowed();
            case CASHIER -> configuration.isCashierStockOverrideAllowed();
            case SUPER_ADMIN -> true;
        };
    }

    public boolean isWarrantyAllowedForRole(Role role) {
        return isWarrantyAllowedForRole(role, resolveRuntimeBranchId());
    }

    public boolean isWarrantyAllowedForRole(Role role, Long branchId) {
        if (role == Role.SUPER_ADMIN) {
            return true;
        }

        AppConfiguration configuration = getOrDefault(branchId);
        if (!configuration.isWarrantyEnabled()) {
            return false;
        }
        return switch (role) {
            case ADMIN -> configuration.isAdminWarrantyAllowed();
            case MANAGER -> configuration.isManagerWarrantyAllowed();
            case CASHIER -> configuration.isCashierWarrantyAllowed();
            case SUPER_ADMIN -> true;
        };
    }

    public boolean isKotEnabled() {
        return isKotEnabled(resolveRuntimeBranchId());
    }

    public boolean isKotEnabled(Long branchId) {
        return getOrDefault(branchId).isKotEnabled();
    }

    private void applyRequest(AppConfiguration configuration, AppConfigurationRequest request) {
        if (planSupportsRecipeItems()) {
            configuration.setRecipeItemsEnabled(request.isRecipeItemsEnabled());
        }
        if (planSupportsWeightItems()) {
            configuration.setWeightItemsEnabled(request.isWeightItemsEnabled());
        }
        if (planSupportsServices()) {
            configuration.setServicesEnabled(request.isServicesEnabled());
        }
        if (planSupportsDiningTables()) {
            configuration.setTableManagementEnabled(request.isTableManagementEnabled());
            configuration.setDineInEnabled(request.isDineInEnabled() && request.isTableManagementEnabled());
        }
        configuration.setCategoryMode(request.getCategoryMode() != null
                ? request.getCategoryMode()
                : CategoryMode.MAIN_AND_SUB);
        configuration.setStockOverrideMode(request.getStockOverrideMode() != null
                ? request.getStockOverrideMode()
                : StockOverrideMode.MANAGER_OVERRIDE);
        if (request.getAdminStockOverrideAllowed() != null) {
            configuration.setAdminStockOverrideAllowed(request.getAdminStockOverrideAllowed());
        }
        if (request.getManagerStockOverrideAllowed() != null) {
            configuration.setManagerStockOverrideAllowed(request.getManagerStockOverrideAllowed());
        }
        if (request.getCashierStockOverrideAllowed() != null) {
            configuration.setCashierStockOverrideAllowed(request.getCashierStockOverrideAllowed());
        }
        if (request.getWarrantyEnabled() != null) {
            configuration.setWarrantyEnabled(request.getWarrantyEnabled());
        }
        if (request.getKotEnabled() != null) {
            configuration.setKotEnabled(request.getKotEnabled());
        }
        if (request.getAdminWarrantyAllowed() != null) {
            configuration.setAdminWarrantyAllowed(request.getAdminWarrantyAllowed());
        }
        if (request.getManagerWarrantyAllowed() != null) {
            configuration.setManagerWarrantyAllowed(request.getManagerWarrantyAllowed());
        }
        if (request.getCashierWarrantyAllowed() != null) {
            configuration.setCashierWarrantyAllowed(request.getCashierWarrantyAllowed());
        }
    }

    private AppConfiguration getOrDefault(Long branchId) {
        Long normalizedBranchId = normalizeBranchId(branchId);
        if (normalizedBranchId != null) {
            return appConfigurationRepository.findByTenantIdAndBranchId(currentTenantId(), normalizedBranchId)
                    .orElseGet(() -> getOrDefault(null));
        }

        return appConfigurationRepository.findByTenantIdAndBranchIdIsNull(currentTenantId())
                .orElseGet(this::buildDefaultConfiguration);
    }

    private Long resolveRequestedBranchId(Long requestedBranchId, boolean forUpdate) {
        Long normalizedRequestedBranchId = normalizeBranchId(requestedBranchId);
        User user = getLoggedUserOrNull();
        if (user == null) {
            return normalizedRequestedBranchId;
        }

        Role role = user.getRole();
        if (role == Role.ADMIN || role == Role.SUPER_ADMIN) {
            if (normalizedRequestedBranchId != null) {
                ensureBranchExists(normalizedRequestedBranchId);
            }
            return normalizedRequestedBranchId;
        }

        if (role == Role.MANAGER || role == Role.CASHIER) {
            Long assignedBranchId = requireAssignedBranch(user);
            if (normalizedRequestedBranchId != null && !assignedBranchId.equals(normalizedRequestedBranchId)) {
                throw new BadRequestException("Cannot access another branch configuration");
            }
            ensureBranchExists(assignedBranchId);
            return assignedBranchId;
        }

        if (forUpdate) {
            throw new BadRequestException("Unsupported role for app configuration update");
        }
        return normalizedRequestedBranchId;
    }

    private Long resolveRuntimeBranchId() {
        User user = getLoggedUserOrNull();
        if (user == null) {
            return null;
        }
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN) {
            return null;
        }
        return user.getBranchId();
    }

    private User getLoggedUserOrNull() {
        try {
            return authService.getLoggedUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long requireAssignedBranch(User user) {
        if (user.getBranchId() == null || user.getBranchId() <= 0) {
            throw new BadRequestException("User branch is not assigned");
        }
        return user.getBranchId();
    }

    private void ensureBranchExists(Long branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
        if (!branch.isActive()) {
            throw new BadRequestException("Branch is inactive");
        }
    }

    private Long normalizeBranchId(Long branchId) {
        return branchId != null && branchId > 0 ? branchId : null;
    }

    private String currentTenantId() {
        String tenantId = TenantContext.getTenant();
        return tenantId != null ? tenantId : "MASTER";
    }

    private AppConfiguration buildDefaultConfiguration() {
        return buildScopedConfiguration(null, null);
    }

    private AppConfiguration buildScopedConfiguration(Long branchId, AppConfiguration base) {
        AppConfiguration source = base != null ? base : AppConfiguration.builder().build();
        return AppConfiguration.builder()
                .branchId(branchId)
                .recipeItemsEnabled(base != null ? source.isRecipeItemsEnabled() : true)
                .weightItemsEnabled(base != null ? source.isWeightItemsEnabled() : true)
                .servicesEnabled(base != null ? source.isServicesEnabled() : true)
                .tableManagementEnabled(base != null ? source.isTableManagementEnabled() : true)
                .dineInEnabled(base != null ? source.isDineInEnabled() : true)
                .categoryMode(source.getCategoryMode() != null ? source.getCategoryMode() : CategoryMode.MAIN_AND_SUB)
                .stockOverrideMode(source.getStockOverrideMode() != null ? source.getStockOverrideMode() : StockOverrideMode.MANAGER_OVERRIDE)
                .adminStockOverrideAllowed(base != null ? source.isAdminStockOverrideAllowed() : true)
                .managerStockOverrideAllowed(base != null ? source.isManagerStockOverrideAllowed() : true)
                .cashierStockOverrideAllowed(base != null && source.isCashierStockOverrideAllowed())
                .warrantyEnabled(base == null || source.isWarrantyEnabled())
                .kotEnabled(base == null || source.isKotEnabled())
                .adminWarrantyAllowed(base == null || source.isAdminWarrantyAllowed())
                .managerWarrantyAllowed(base == null || source.isManagerWarrantyAllowed())
                .cashierWarrantyAllowed(base != null && source.isCashierWarrantyAllowed())
                .build();
    }

    private AppConfigurationResponse mapEffective(AppConfiguration configuration, Long branchId) {
        return AppConfigurationResponse.builder()
                .branchId(branchId)
                .recipeItemsEnabled(planSupportsRecipeItems() && configuration.isRecipeItemsEnabled())
                .weightItemsEnabled(planSupportsWeightItems() && configuration.isWeightItemsEnabled())
                .servicesEnabled(planSupportsServices() && configuration.isServicesEnabled())
                .tableManagementEnabled(planSupportsDiningTables() && configuration.isTableManagementEnabled())
                .dineInEnabled(planSupportsDiningTables() && configuration.isTableManagementEnabled() && configuration.isDineInEnabled())
                .categoryMode(configuration.getCategoryMode() != null
                        ? configuration.getCategoryMode()
                        : CategoryMode.MAIN_AND_SUB)
                .stockOverrideMode(configuration.getStockOverrideMode() != null
                        ? configuration.getStockOverrideMode()
                        : StockOverrideMode.MANAGER_OVERRIDE)
                .adminStockOverrideAllowed(configuration.isAdminStockOverrideAllowed())
                .managerStockOverrideAllowed(configuration.isManagerStockOverrideAllowed())
                .cashierStockOverrideAllowed(configuration.isCashierStockOverrideAllowed())
                .warrantyEnabled(configuration.isWarrantyEnabled())
                .kotEnabled(configuration.isKotEnabled())
                .adminWarrantyAllowed(configuration.isAdminWarrantyAllowed())
                .managerWarrantyAllowed(configuration.isManagerWarrantyAllowed())
                .cashierWarrantyAllowed(configuration.isCashierWarrantyAllowed())
                .build();
    }

    private boolean planSupportsRecipeItems() {
        String planName = currentPlanName();
        return isLegacyOrUnknownPlan(planName) || isProPlan(planName);
    }

    private boolean planSupportsWeightItems() {
        String planName = currentPlanName();
        return isLegacyOrUnknownPlan(planName) || isStandardPlan(planName) || isProPlan(planName);
    }

    private boolean planSupportsServices() {
        String planName = currentPlanName();
        return isLegacyOrUnknownPlan(planName) || isStandardPlan(planName) || isProPlan(planName);
    }

    private boolean planSupportsDiningTables() {
        String planName = currentPlanName();
        return isLegacyOrUnknownPlan(planName) || isStandardPlan(planName) || isProPlan(planName);
    }

    private String currentPlanName() {
        String tenantId = TenantContext.getTenant();
        if (tenantId == null || "MASTER".equals(tenantId)) {
            return "";
        }
        return tenantSubscriptionRepository.findByTenantId(tenantId)
                .map(subscription -> subscription.getPlan().getName())
                .map(planName -> planName == null ? "" : planName.trim().toUpperCase())
                .orElse("");
    }

    private boolean isStandardPlan(String planName) {
        return "STANDARD".equals(planName)
                || "MONTHLY_LITE".equals(planName)
                || "YEARLY_LITE".equals(planName)
                || "MONTHLY_BASIC".equals(planName);
    }

    private boolean isProPlan(String planName) {
        return "PRO".equals(planName)
                || "MONTHLY_PRO".equals(planName)
                || "YEARLY_PRO".equals(planName);
    }

    private boolean isLegacyOrUnknownPlan(String planName) {
        return !isFreePlan(planName) && !isStandardPlan(planName) && !isProPlan(planName);
    }

    private boolean isFreePlan(String planName) {
        return "FREE".equals(planName) || "MONTHLY_DEMO".equals(planName);
    }
}
