package com.chala.posapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return buildProblem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NotAssignedException.class)
    public ProblemDetail handleNotAssigned(NotAssignedException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(AlreadyExistsException.class)
    public ProblemDetail handleAlreadyExists(AlreadyExistsException ex) {
        return buildProblem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(StockOverrideRequiredException.class)
    public ProblemDetail handleStockOverrideRequired(StockOverrideRequiredException ex) {
        ProblemDetail problemDetail = buildProblem(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setProperty("code", "STOCK_OVERRIDE_REQUIRED");
        problemDetail.setProperty("overrideAvailable", ex.isOverrideAvailable());
        problemDetail.setProperty("shortages", ex.getShortages());
        return problemDetail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problemDetail = buildProblem(HttpStatus.BAD_REQUEST, "Validation Failed");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Request could not be completed because of invalid or duplicate data.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllExceptions(Exception ex) {
        ex.printStackTrace();

        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our end. Please try again later.");
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return buildProblem(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    private ProblemDetail buildProblem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty("message", detail);
        problemDetail.setProperty("status", status.value());
        return problemDetail;
    }
}
