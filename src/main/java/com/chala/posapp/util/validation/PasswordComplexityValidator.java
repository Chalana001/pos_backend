package com.chala.posapp.util.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * MISS-08: Implements the logic for @PasswordComplexity.
 *
 * Pattern explanation:
 *  (?=.*[A-Z])   — at least one uppercase letter
 *  (?=.*\d)      — at least one digit
 *  (?=.*[!@#$%^&*()_+\-=\[\]{};':"\|,.<>/?]) — at least one special char
 *  .{8,100}      — total length 8–100
 */
public class PasswordComplexityValidator
        implements ConstraintValidator<PasswordComplexity, String> {

    private static final Pattern PATTERN = Pattern.compile(
        "^(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{8,100}$"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isBlank()) {
            // Nullability is handled by @NotBlank — don't duplicate
            return true;
        }
        return PATTERN.matcher(value).matches();
    }
}
