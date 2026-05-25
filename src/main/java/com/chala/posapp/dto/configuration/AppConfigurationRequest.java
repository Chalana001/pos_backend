package com.chala.posapp.dto.configuration;

import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.StockOverrideMode;
import lombok.Data;

@Data
public class AppConfigurationRequest {
    private boolean recipeItemsEnabled;
    private boolean weightItemsEnabled;
    private boolean servicesEnabled;
    private boolean tableManagementEnabled;
    private boolean dineInEnabled;
    private CategoryMode categoryMode;
    private StockOverrideMode stockOverrideMode;
}
