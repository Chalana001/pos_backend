package com.chala.posapp.dto.saas.module;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Batch module change for one shop. The panel sends every toggle the admin flipped in one call
 * so the whole edit lands as a single audit entry rather than one per switch.
 *
 * <p>{@code enabled == null} means "remove the override and follow the plan again" — that is the
 * reset-to-plan action, and it is why this is a tri-state rather than a boolean.
 */
public record ModuleToggleRequest(
        @NotEmpty(message = "At least one module change is required")
        @Valid List<Change> changes,
        String note
) {
    public record Change(
            @NotBlank(message = "moduleKey is required") String moduleKey,
            Boolean enabled
    ) {
    }
}
