package com.chala.posapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an account has failed too many logins in a row and is temporarily locked.
 *
 * <p>429 rather than 401 on purpose: 401 tells the caller "wrong password, try again", which
 * is exactly the wrong advice here, and the POS app's axios interceptor treats a 401 as an
 * expired session and bounces the user to the login screen.
 */
@ResponseStatus(value = HttpStatus.TOO_MANY_REQUESTS)
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
