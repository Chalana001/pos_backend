package com.chala.posapp.dto.configuration;

import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.StockOverrideMode;
import lombok.Data;

@Data
public class AppConfigurationRequest {
    private Long branchId;
    private boolean recipeItemsEnabled;
    private boolean weightItemsEnabled;
    private boolean servicesEnabled;
    private boolean tableManagementEnabled;
    private boolean dineInEnabled;
    private CategoryMode categoryMode;
    private StockOverrideMode stockOverrideMode;
    private Boolean adminStockOverrideAllowed;
    private Boolean managerStockOverrideAllowed;
    private Boolean cashierStockOverrideAllowed;
    private Boolean warrantyEnabled;
    private Boolean kotEnabled;
    private Boolean adminWarrantyAllowed;
    private Boolean managerWarrantyAllowed;
    private Boolean cashierWarrantyAllowed;
}
