package com.chala.posapp.module;

/**
 * Grouping used purely for presentation in the super admin panel.
 * Adding a category here does not change enforcement.
 */
public enum ModuleCategory {
    SALES("Sales & Checkout"),
    INVENTORY("Inventory"),
    PURCHASING("Purchasing"),
    FINANCE("Cash & Finance"),
    RELATIONSHIPS("Customers & Suppliers"),
    INSIGHTS("Reports & Insights"),
    ADMIN("Administration");

    private final String label;

    ModuleCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
