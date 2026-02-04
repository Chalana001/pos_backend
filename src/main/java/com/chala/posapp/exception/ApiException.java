package com.chala.posapp.exception;

import org.springframework.http.HttpStatus;

@SuppressWarnings("unused")
public abstract class ApiException extends RuntimeException {
    private final HttpStatus status;

    protected ApiException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}
