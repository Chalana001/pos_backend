package com.chala.posapp.service;

import com.chala.posapp.dto.configuration.AppConfigurationRequest;
import com.chala.posapp.dto.configuration.AppConfigurationResponse;
import com.chala.posapp.entity.AppConfiguration;
import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.StockOverrideMode;
import com.chala.posapp.repository.AppConfigurationRepository;
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

    public AppConfigurationResponse getConfiguration() {
        return mapEffective(getOrDefault());
    }

    @Transactional
    public AppConfigurationResponse updateConfiguration(AppConfigurationRequest request) {
        AppConfiguration configuration = appConfigurationRepository.findFirstByOrderByIdAsc()
                .orElseGet(this::buildDefaultConfiguration);

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

        return mapEffective(appConfigurationRepository.save(configuration));
    }

    public boolean isItemTypeEnabled(ItemType itemType) {
        AppConfiguration configuration = getOrDefault();
        return switch (itemType) {
            case NORMAL -> true;
            case WEIGHT -> planSupportsWeightItems() && configuration.isWeightItemsEnabled();
            case VOLUME -> planSupportsWeightItems() && configuration.isWeightItemsEnabled();
            case SERVICE -> planSupportsServices() && configuration.isServicesEnabled();
            case RECIPE -> planSupportsRecipeItems() && configuration.isRecipeItemsEnabled();
        };
    }

    public boolean isTableManagementEnabled() {
        return planSupportsDiningTables() && getOrDefault().isTableManagementEnabled();
    }

    public boolean isDineInEnabled() {
        AppConfiguration configuration = getOrDefault();
        return planSupportsDiningTables() && configuration.isTableManagementEnabled() && configuration.isDineInEnabled();
    }

    public StockOverrideMode getStockOverrideMode() {
        StockOverrideMode mode = getOrDefault().getStockOverrideMode();
        return mode != null ? mode : StockOverrideMode.MANAGER_OVERRIDE;
    }

    private AppConfiguration getOrDefault() {
        return appConfigurationRepository.findFirstByOrderByIdAsc()
                .orElseGet(this::buildDefaultConfiguration);
    }

    private AppConfiguration buildDefaultConfiguration() {
        return AppConfiguration.builder()
                .recipeItemsEnabled(true)
                .weightItemsEnabled(true)
                .servicesEnabled(true)
                .tableManagementEnabled(true)
                .dineInEnabled(true)
                .categoryMode(CategoryMode.MAIN_AND_SUB)
                .stockOverrideMode(StockOverrideMode.MANAGER_OVERRIDE)
                .build();
    }

    private AppConfigurationResponse mapEffective(AppConfiguration configuration) {
        return AppConfigurationResponse.builder()
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
