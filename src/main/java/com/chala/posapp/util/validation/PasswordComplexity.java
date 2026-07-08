package com.chala.posapp.util.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * MISS-08: Password complexity constraint.
 *
 * Rules enforced:
 *  - Minimum 8 characters
 *  - At least 1 uppercase letter (A-Z)
 *  - At least 1 digit (0-9)
 *  - At least 1 special character (!@#$%^&*...)
 *
 * Usage: annotate any password field in a DTO with @PasswordComplexity.
 * The validator runs automatically when the controller has @Valid on the request body.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PasswordComplexityValidator.class)
public @interface PasswordComplexity {

    String message() default
        "Password must be at least 8 characters and contain an uppercase letter, a digit, and a special character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
