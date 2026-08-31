package com.chala.posapp.audit;

import java.lang.annotation.*;

/**
 * MISS-03: Mark a service method for automatic audit logging.
 *
 * Usage:
 *   @Audited(entity = "ORDER", action = "CREATE")
 *   public OrderResponse createOrder(...)
 *
 * The AuditAspect intercepts the call, extracts the current user from
 * SecurityContext, then persists an {@link com.chala.posapp.entity.AuditLog}
 * entry after the method returns successfully.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** Logical entity type — e.g. "ORDER", "USER", "EXPENSE", "GRN". */
    String entity();

    /** Action performed — e.g. "CREATE", "UPDATE", "CANCEL", "DELETE", "RESET_PASSWORD". */
    String action();

    /**
     * Optional SpEL expression evaluated against the method arguments to extract
     * the entity ID. Examples: "#request.branchId", "#id", "#invoiceNo".
     * Leave empty to skip ID capture.
     */
    String idExpression() default "";

    /**
     * Optional SpEL expression for a brief, log-safe summary string.
     * Example: "'Order #' + #request.branchId".
     */
    String summaryExpression() default "";
}
