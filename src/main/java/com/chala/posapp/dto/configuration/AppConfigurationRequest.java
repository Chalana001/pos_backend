package com.chala.posapp.dto.configuration;

import lombok.Data;

@Data
public class AppConfigurationRequest {
    private boolean recipeItemsEnabled;
    private boolean weightItemsEnabled;
    private boolean servicesEnabled;
    private boolean tableManagementEnabled;
    private boolean dineInEnabled;
}
