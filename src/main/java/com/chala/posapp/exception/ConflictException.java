package com.chala.posapp.exception;

/**
 * The request is well-formed, but the current state of the record forbids it.
 *
 * This type exists because the service layer had no reliable way to answer 409.
 * ItemService reaches for Spring's ResponseStatusException, but
 * GlobalExceptionHandler's catch-all @ExceptionHandler(Exception.class) is the
 * matching handler for it and turns it into a 500 "Something went wrong", so that
 * status never reaches the client. An explicit type with its own handler is what
 * actually surfaces as a 409.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
