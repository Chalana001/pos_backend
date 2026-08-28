package com.chala.posapp.dto.saas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Request shapes for discount codes and announcements.
 *
 * <p>Deliberately not the JPA entities. Binding an entity to a request body lets a client set
 * fields it has no business setting ({@code usedCount}, {@code createdBy}, {@code createdAt}),
 * and Jackson binds records by their canonical constructor, so an absent field arrives as a
 * well-defined {@code null} rather than blowing up against a primitive.
 */
public final class LifecycleDtos {

    private LifecycleDtos() {
    }

    /** Boxed types throughout: absent and zero are different answers here. */
    public record DiscountRequest(
            @NotBlank(message = "Code is required")
            @Size(max = 40)
            String code,

            @Size(max = 255)
            String description,

            @NotBlank(message = "Discount type is required")
            @Pattern(regexp = "PERCENT|FIXED", message = "Discount type must be PERCENT or FIXED")
            String discountType,

            @Positive(message = "Discount value must be greater than zero")
            Double value,

            LocalDateTime validFrom,
            LocalDateTime validUntil,

            /** Null means unlimited uses. */
            Integer maxUses,

            /** Comma-separated plan ids, or null for any plan. */
            String appliesToPlanIds,

            Boolean active
    ) {
    }

    public record AnnouncementRequest(
            @NotBlank(message = "Title is required")
            @Size(max = 160)
            String title,

            @NotBlank(message = "Message body is required")
            String body,

            @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "Severity must be INFO, WARNING or CRITICAL")
            String severity,

            @Pattern(regexp = "ALL|PLAN|TENANT|MODULE", message = "Audience must be ALL, PLAN, TENANT or MODULE")
            String audience,

            @Size(max = 255)
            String audienceValue,

            LocalDateTime activeFrom,
            LocalDateTime activeUntil,

            Boolean dismissible,
            Boolean published,

            @Size(max = 500)
            String linkUrl,

            @Size(max = 80)
            String linkLabel
    ) {
    }
}
