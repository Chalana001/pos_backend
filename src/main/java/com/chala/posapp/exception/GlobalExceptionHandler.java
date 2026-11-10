package com.chala.posapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicatePurchaseRowsException.class)
    public ProblemDetail handleDuplicatePurchaseRows(DuplicatePurchaseRowsException ex) {
        ProblemDetail problem = buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "DUPLICATE_PURCHASE_ROWS");
        problem.setProperty("duplicates", ex.getDuplicates().stream().map(duplicate -> {
            Map<String, Object> detail = new HashMap<>();
            detail.put("rowIndex", duplicate.rowIndex());
            detail.put("duplicateOfRowIndex", duplicate.duplicateOfRowIndex());
            detail.put("itemId", duplicate.itemId());
            return detail;
        }).toList());
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return buildProblem(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(NotAssignedException.class)
    public ProblemDetail handleNotAssigned(NotAssignedException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, ex.getMessage(), "NOT_ASSIGNED");
    }

    @ExceptionHandler(AlreadyExistsException.class)
    public ProblemDetail handleAlreadyExists(AlreadyExistsException ex) {
        return buildProblem(HttpStatus.CONFLICT, ex.getMessage(), "ALREADY_EXISTS");
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, ex.getMessage(), "BAD_REQUEST");
    }

    @ExceptionHandler(StockOverrideRequiredException.class)
    public ProblemDetail handleStockOverrideRequired(StockOverrideRequiredException ex) {
        ProblemDetail problemDetail = buildProblem(HttpStatus.CONFLICT, ex.getMessage(), "STOCK_OVERRIDE_REQUIRED");
        problemDetail.setProperty("overrideAvailable", ex.isOverrideAvailable());
        problemDetail.setProperty("shortages", ex.getShortages());
        return problemDetail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, "You do not have permission to access this resource.", "FORBIDDEN");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problemDetail = buildProblem(HttpStatus.BAD_REQUEST, "Validation Failed", "VALIDATION_FAILED");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String correlationId = correlationId();
        log.error("Database integrity failure correlationId={} method={} path={}", correlationId,
                requestMethod(), requestPath(), ex);
        return buildProblem(HttpStatus.BAD_REQUEST,
                "Request could not be completed because of invalid or duplicate data.",
                "DATA_INTEGRITY_VIOLATION", correlationId);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return buildProblem(HttpStatus.CONFLICT, ex.getMessage(), "CONFLICT");
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ProblemDetail handleTooManyAttempts(TooManyAttemptsException ex) {
        return buildProblem(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), "TOO_MANY_ATTEMPTS");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllExceptions(Exception ex) {
        String correlationId = correlationId();
        log.error("Unhandled request failure correlationId={} method={} path={}", correlationId,
                requestMethod(), requestPath(), ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our end. Please try again later.",
                "INTERNAL_SERVER_ERROR", correlationId);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return buildProblem(HttpStatus.UNAUTHORIZED, "Invalid username or password", "INVALID_CREDENTIALS");
    }

    private ProblemDetail buildProblem(HttpStatus status, String detail) {
        return buildProblem(status, detail, codeFor(status), correlationId());
    }

    private ProblemDetail buildProblem(HttpStatus status, String detail, String code) {
        return buildProblem(status, detail, code, correlationId());
    }

    private ProblemDetail buildProblem(HttpStatus status, String detail, String code, String correlationId) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty("success", false);
        problemDetail.setProperty("message", detail);
        problemDetail.setProperty("status", status.value());
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("correlationId", correlationId);
        return problemDetail;
    }

    private String codeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case FORBIDDEN -> "FORBIDDEN";
            case CONFLICT -> "CONFLICT";
            case UNPROCESSABLE_ENTITY -> "BUSINESS_VALIDATION_FAILED";
            default -> "BAD_REQUEST";
        };
    }

    private String correlationId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return UUID.randomUUID().toString();
        var request = attributes.getRequest();
        Object existing = request.getAttribute("correlationId");
        if (existing != null) return existing.toString();
        String supplied = request.getHeader("X-Correlation-ID");
        String id;
        try {
            id = supplied == null ? UUID.randomUUID().toString()
                    : java.util.UUID.fromString(supplied).toString();
        } catch (IllegalArgumentException ex) {
            id = UUID.randomUUID().toString();
        }
        request.setAttribute("correlationId", id);
        return id;
    }

    private String requestMethod() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? "unknown" : attributes.getRequest().getMethod();
    }

    private String requestPath() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? "unknown" : attributes.getRequest().getRequestURI();
    }

}
