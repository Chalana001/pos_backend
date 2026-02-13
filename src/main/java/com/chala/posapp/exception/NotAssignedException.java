package com.chala.posapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.FORBIDDEN)
public class NotAssignedException extends RuntimeException {
    public NotAssignedException(String message) {
        super(message);
    }
}
