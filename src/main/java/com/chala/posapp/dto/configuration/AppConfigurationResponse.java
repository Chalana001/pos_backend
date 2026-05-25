package com.chala.posapp.dto.configuration;

import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.StockOverrideMode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AppConfigurationResponse {
    private boolean recipeItemsEnabled;
    private boolean weightItemsEnabled;
    private boolean servicesEnabled;
    private boolean tableManagementEnabled;
    private boolean dineInEnabled;
    private CategoryMode categoryMode;
    private StockOverrideMode stockOverrideMode;
}
