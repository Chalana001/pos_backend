package com.chala.posapp.dto.reorder;
public record ReorderPlanRequest(String name, Long branchId, int forecastDays, int targetCoverDays, String notes) {}
