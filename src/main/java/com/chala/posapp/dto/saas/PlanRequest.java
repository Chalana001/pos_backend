package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;

/**
 * Create or edit a subscription plan from the panel.
 *
 * @param copyModulesFromPlanId on create only — start the new plan's module template as a copy
 *                              of an existing plan instead of everything-on. Ignored on update.
 */
public record PlanRequest(
        @NotBlank(message = "Plan name is required")
        @Pattern(regexp = "^[A-Za-z0-9_ -]{2,60}$",
                message = "Plan name may only contain letters, numbers, spaces, hyphens and underscores")
        String name,

        @NotBlank(message = "Billing cycle is required")
        @Pattern(regexp = "MONTHLY|YEARLY", message = "Billing cycle must be MONTHLY or YEARLY")
        String billingCycle,

        @PositiveOrZero(message = "Initial price cannot be negative")
        double initialPrice,

        @PositiveOrZero(message = "Renewal price cannot be negative")
        double renewalPrice,

        @Min(value = 1, message = "A plan must allow at least one branch")
        int maxBranches,

        String description,
        String color,
        Integer displayOrder,
        Boolean active,
        Long copyModulesFromPlanId
) {
}
